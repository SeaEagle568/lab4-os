package lab.oleksiienko;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static lab.oleksiienko.Important.FindArgs.*;
import static lab.oleksiienko.Important.Status.*;


public class Important {
    private static final String HELP_TEXT = "Usage: important COMMAND [OPTIONS] [FILE...]\n\n" +
            "A tool to mark and find the files as IMPORTANT.\n\n" +
            "Commands:\n" +
            "\tmark FILE...     Marks the files as lab.oleksiienko.Important\n" +
            "\tunmark FILE...   Unmarks the files as lab.oleksiienko.Important\n" +
            "\tfind [OPTIONS]   Finds important files and prints absolute paths on new lines\n\n" +
            "Try 'important find --help' to get more information on the 'find' command.\n\n" +
            "Exit codes:\n" +
            "\t (0) - OK\n" +
            "\t (-1) - Invalid syntax\n" +
            "\t (has byte: 2) - Permission error, check stderr\n" +
            "\t (has byte: 4) - File not found, check stderr\n" +
            "\t (has byte: 8) - System I/O error happened, check stderr\n" +
            "\t (has byte: 16) - Uncertain, could be OK, double check manually\n";
    private static final String FIND_HELP = "Usage: important find [--dir search_directory] [--ext extension] [--name-contains string] [-v|--verbose] [--use-regexp]"; //find help lol
    private static final String NO_FILE_T = "important: error on %s: file does not exist or current user does not have permissions to locate it.%n";

    private static final String ATTRIBUTES_NOT_SUPPORTED_T = "important: warning on %s: it may be that file system does not support extended file arguments. falling back to the additional file approach.\n" +
            "note: files 'importance' will not be preserved on copying/moving.%n";
    private static final String UNKNOWN_COMMAND_T = "important: error: '%s' is not a known command.\n" +
            "See 'important --help'.%n";

    private static final String NO_PERMISSION_T = "important: error on %s: current user does not have the '%s' permissions on this file/directory.%n";
    private static final String SYSTEM_IO_ERR_T = "important: error on %s: System I/O error occurred while trying to %s. Underlying error message: '%s'.%n";
    private static final String SYNTAX_ERR_T = "important: error: Invalid syntax. %s%n";
    private static final String DIRECTORY_T = "important: warning on %s: This is a directory, skipping... %n";

    private static final int KiB64 = 65536; //unix max attribute size
    private static final String ATTR_NAME = "imp";

    public static void main(String[] args) {
        if (args.length == 0 || (args.length == 1 && "--help".equals(args[0]))) {
            System.out.println(HELP_TEXT);
            System.exit(OK.code);
        }
        switch (args[0]) {
            case "mark" : {
                updateMark(args, false);
                break;
            }
            case "unmark" : {
                updateMark(args, true);
                break;
            }
            case "find" : {
                find(args);
                break;
            }
            default: {
                System.err.printf(UNKNOWN_COMMAND_T, args[0]);
                System.exit(INVALID.code);
            }
        }
    }

    private static void updateMark(String[] args, boolean remove) {
        Status status = OK;
        if (args.length == 1) {
            System.err.println("Why not provide some files?");
        }
        List<String> absolutePaths = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            File file = new File(arg);
            try {
                if (!file.exists()) {
                    System.err.printf(NO_FILE_T, arg);
                    status = ERR_NOT_FOUND;
                    continue;
                }
                if (file.isDirectory()) {
                    System.err.printf(DIRECTORY_T, arg);
                    continue;
                }
                absolutePaths.add(file.getAbsolutePath());
                var attributes = Files.getFileAttributeView(
                        file.toPath(),
                        UserDefinedFileAttributeView.class
                );
                if (attributes == null) {
                    System.err.printf(ATTRIBUTES_NOT_SUPPORTED_T, arg);
                    status = status.or(
                            (remove ? OK   //We remove all files from there afterward.
                                    : doMarkFile(file)));
                } else {
                    status = status.or(
                            (remove ? doUnmarkAttr(file, attributes)
                                    : doMarkAttr(file, attributes)));
                }
            } catch (SecurityException secEx) {
                    System.err.printf(NO_PERMISSION_T, arg, "read");
                    status = ERR_PERM;
            }
        }
        if (remove) {
            status = status.or(doUnmarkFile(absolutePaths));
        }
        System.exit(status.code);
    }

    private static void find(String[] args) {
        if (args.length > 1 && ("--help".equals(args[1]))) {
            System.out.println(FIND_HELP);
            System.exit(OK.code);
        }
        try {
            HashMap<FindArgs, String> arguments = parseArgs(args);
            String dir = System.getProperty("user.dir");
            if (arguments.containsKey(DIR)) {
                dir = arguments.get(DIR);
            }
            String nameRegexp = ".*";
            if (arguments.containsKey(NAME_CONTAINS)) {
                nameRegexp += Pattern.quote(arguments.get(NAME_CONTAINS)) + ".*";
                if (arguments.containsKey(USE_REGEXP)) {
                    nameRegexp = arguments.get(NAME_CONTAINS) + "(\\..*)?";
                }
            }
            String extRegexp = ".*";
            if (arguments.containsKey(EXT)) {
                extRegexp = ".*\\." + Pattern.quote(arguments.get(EXT));
                if (arguments.containsKey(USE_REGEXP)) {
                    extRegexp = ".*\\." + arguments.get(EXT);
                }
            }
            if (arguments.containsKey(VERBOSE)) {
                System.err.println("Searching in the directory: " + dir);
                System.err.println("Name regexp: " + nameRegexp);
                System.err.println("Extension regexp: " + extRegexp);
            }
            Pattern namePattern = Pattern.compile(nameRegexp);
            Pattern extPattern = Pattern.compile(extRegexp);

            try (var walk = Files.walk(Paths.get(dir))) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> namePattern.matcher(path.getFileName().toString()).matches())
                        .filter(path -> extPattern.matcher(path.getFileName().toString()).matches())
                        .filter(path -> isMarked(path.toAbsolutePath()))
                        .forEach((path -> System.out.println(path.toAbsolutePath())));
            } catch (SecurityException secEx) {
                System.err.printf(NO_PERMISSION_T, "find", "read");
                System.exit(ERR_PERM.code);
            } catch (PatternSyntaxException patternEx) {
                System.err.printf(SYNTAX_ERR_T, "RegExp " + patternEx.getPattern() +  " is not valid! " + patternEx.getMessage());
                System.exit(INVALID.code);
            } catch (IOException | UnsupportedOperationException | UncheckedIOException e) {
                if (e.getCause() instanceof AccessDeniedException) {
                    System.err.printf(NO_PERMISSION_T, "find", "read");
                    System.exit(ERR_PERM.code);
                }
                System.err.printf(SYSTEM_IO_ERR_T, "find", "traverse the " + dir + " directory", e.getMessage());
                System.exit(ERR_IO.code);
            }
        } catch (IllegalArgumentException e) {
            System.err.printf(SYNTAX_ERR_T, e.getMessage());
            System.exit(INVALID.code);
        }
    }

    private static boolean isMarked(Path absolutePath) {
        try {
            var attributes = Files.getFileAttributeView(
                    absolutePath,
                    UserDefinedFileAttributeView.class
            );
            if (attributes == null) {
                return isMarkedInFile(absolutePath);
            }
            if (!attributes.list().contains(ATTR_NAME)) {
                return false;
            }
            ByteBuffer validate = ByteBuffer.allocate(KiB64);
            attributes.read(ATTR_NAME, validate);
            validate.rewind();
            return (validate.get() == 'y' || isMarkedInFile(absolutePath));
        } catch (IOException | UncheckedIOException e) {
            if (e.getCause() instanceof AccessDeniedException) {
                System.err.printf(NO_PERMISSION_T, "search", "read (attributes)");
                return false;
            }
            System.err.printf(SYSTEM_IO_ERR_T, absolutePath, "check if marked", e.getMessage());
            return false;
        } catch (SecurityException secEx) {
            System.err.printf(NO_PERMISSION_T, "search", "read (attributes)");
            return false;
        }
    }

    private static boolean isMarkedInFile(Path absolutePath) {
        try {
            File catalog = new File(".important");
            if (!catalog.exists()) {
                return false;
            }
            var lines = Files.readAllLines(catalog.toPath());
            return lines.contains(absolutePath.toString());
        } catch (IOException | UncheckedIOException e) {
            if (e.getCause() instanceof AccessDeniedException) {
                System.err.printf(NO_PERMISSION_T, "search", "read an additional attributes file");
                return false;
            }
            System.err.printf(SYSTEM_IO_ERR_T, "search", "read an additional attributes file", e.getMessage());
            return false;
        } catch (SecurityException secEx) {
            System.err.printf(NO_PERMISSION_T, "search", "read an additional attributes file");
            return false;
        }
    }

    private static HashMap<FindArgs, String> parseArgs(String[] args) {
        HashMap<FindArgs, String> arguments = new HashMap<>();
        for (AtomicInteger i = new AtomicInteger(1); i.get() < args.length; i.getAndIncrement()) {
            final String curArg = args[i.get()];
            if (VERBOSE.name.equals(curArg) || "-v".equals(curArg)) {
                arguments.put(VERBOSE, "true");
                continue;
            } else if (USE_REGEXP.name.equals(curArg)) {
                arguments.put(USE_REGEXP, "true");
                continue;
            }
            final String nextArg = (i.get() == args.length - 1) ? null : args[i.get() +1];
            FindArgs.getByName(curArg).ifPresentOrElse(
                    (name) -> {
                        if (nextArg != null && FindArgs.getByName(nextArg).isEmpty()) {
                            arguments.put(name, nextArg);
                            i.getAndIncrement();
                        } else {
                            throw new IllegalArgumentException("No value present for " + curArg);
                        }
                    },
                    () -> {
                        throw new IllegalArgumentException("Unrecognised argument: " + curArg);
                    }
            );
        }
        return arguments;
    }


    private static Status doUnmarkAttr(File file, UserDefinedFileAttributeView attributes) {
        try {
            if (attributes.list().stream().noneMatch(ATTR_NAME::equals)) {
                return OK; //No such attribute
            }
            attributes.delete(ATTR_NAME);
            if (attributes.list().stream().anyMatch(ATTR_NAME::equals)) {
                return ERR_IO; //attributes deletion failed
            }
            return OK;
        } catch (IOException | UncheckedIOException e) {
            if (e.getCause() instanceof AccessDeniedException) {
                System.err.printf(NO_PERMISSION_T, file.getPath(), "delete attribute");
                System.err.printf(ATTRIBUTES_NOT_SUPPORTED_T, file.getPath());
                return UNCERTAIN;
            }
            System.err.printf(SYSTEM_IO_ERR_T, file.getPath(), "modify file's attribute", e.getMessage());
            System.err.printf(ATTRIBUTES_NOT_SUPPORTED_T, file.getPath());
            return UNCERTAIN; //we don't know if the attribute was there
        } catch (SecurityException secEx) {
            System.err.printf(NO_PERMISSION_T, file.getPath(), "delete attribute");
            System.err.printf(ATTRIBUTES_NOT_SUPPORTED_T, file.getPath());
            return UNCERTAIN; //we don't know if the attribute was there
        }
    }

    private static Status doUnmarkFile(List<String> files) {
        if (files.isEmpty()) {
            return OK;
        }
        try {
            File catalog = new File(".important");
            if (!catalog.exists()) {
                return OK;
            }
            var lines = new HashSet<>(Files.readAllLines(catalog.toPath()));
            for (String file : files) {
                lines.remove(file);
            }
            Files.write(catalog.toPath(),
                    String.join("\n", lines).getBytes());
            return OK;
        } catch (SecurityException secEx) {
            System.err.printf(NO_PERMISSION_T, "unmarking", "read/write");
            return ERR_PERM;
        } catch (IOException | UnsupportedOperationException | UncheckedIOException e) {
            if (e.getCause() instanceof AccessDeniedException) {
                System.err.printf(NO_PERMISSION_T, "unmarking", "read/write");
                return ERR_PERM;
            }
            System.err.printf(SYSTEM_IO_ERR_T, "unmarking", "modify an additional attributes file", e.getMessage());
            return ERR_IO;
        }
    }


    private static Status doMarkAttr(File file, UserDefinedFileAttributeView attributes) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap("y".getBytes(StandardCharsets.UTF_8));
            attributes.write(ATTR_NAME, buffer);
            ByteBuffer validate = ByteBuffer.allocate(KiB64);
            attributes.read(ATTR_NAME, validate);
            validate.rewind();
            if (validate.get() != 'y') {
                System.err.printf(ATTRIBUTES_NOT_SUPPORTED_T, file.getPath());
                return doMarkFile(file);
            }
            System.out.println(file.getPath() + " marked successfully.");
            return OK;
        } catch (IOException | UncheckedIOException e) {
            if (e.getCause() instanceof AccessDeniedException) {
                System.err.printf(NO_PERMISSION_T, file.getPath(), "write (attribute)");
                System.err.printf(ATTRIBUTES_NOT_SUPPORTED_T, file.getPath());
                return doMarkFile(file);
            }
            System.err.printf(SYSTEM_IO_ERR_T, file.getPath(), "modify file's attribute", e.getMessage());
            System.err.printf(ATTRIBUTES_NOT_SUPPORTED_T, file.getPath());
            return doMarkFile(file);
        } catch (SecurityException secEx) {
            System.err.printf(NO_PERMISSION_T, file.getPath(), "write (attribute)");
            System.err.printf(ATTRIBUTES_NOT_SUPPORTED_T, file.getPath());
            return doMarkFile(file);
        }
    }

    private static Status doMarkFile(File file) {
        try {
            File catalog = new File(".important");
            if (!catalog.exists()) {
                boolean created = catalog.createNewFile();
                if (!created) {
                    throw new IOException("File could not be created");
                }
            }
            Files.write(
                    catalog.toPath(),
                    (file.getAbsolutePath() + "\n").getBytes(),
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
            System.out.println(file.getPath() + " marked successfully.");
            return OK;
        } catch (SecurityException secEx) {
            System.err.printf(NO_PERMISSION_T, file.getPath(), "read/create");
            return ERR_PERM;
        } catch (IOException | UnsupportedOperationException | UncheckedIOException e) {
            if (e.getCause() instanceof AccessDeniedException) {
                System.err.printf(NO_PERMISSION_T, file.getPath(), "read/create");
                return ERR_PERM;
            }
            System.err.printf(SYSTEM_IO_ERR_T, file.getPath(), "create an additional attributes file", e.getMessage());
            return ERR_IO;
        }
    }

    enum Status {
        OK(0),
        INVALID(-1),
        ERR_PERM(2),
        ERR_NOT_FOUND(4),
        ERR_IO(8),
        UNCERTAIN(16),
        COMPOSITE(256);

        public int code;

        Status(int code) {
            this.code = code;
        }

        public Status or(Status other) {
            Status composite = Status.COMPOSITE;
            composite.code = (this.code | other.code);
            return composite;
        }
    }

    enum FindArgs {
        DIR("--dir"),
        EXT("--ext"),
        NAME_CONTAINS("--name-contains"),
        VERBOSE("--verbose"),
        USE_REGEXP("--use-regexp");

        public final String name;

        FindArgs(String name) {
            this.name = name;
        }
        public static Optional<FindArgs> getByName(String str) {
            return Arrays.stream(FindArgs.values())
                    .filter((e) -> e.name.equals(str))
                    .findAny();
        }
    }

}