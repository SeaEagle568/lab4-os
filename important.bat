@echo off
setlocal

rem Step 1: Check if 'java' is defined in the path
where java >nul 2>nul
if %errorlevel% neq 0 (
    echo 'java' is not found in the path.

    rem Step 2: Check if JAVA_HOME is defined
    if not defined JAVA_HOME (
        echo JAVA_HOME is not defined. Please set JAVA_HOME environment variable.
        goto :end
    )

    rem Step 2 (cont): Set java path to JAVA_HOME/bin/java.exe
    set "javaPath=%JAVA_HOME%\bin\java.exe"
) else (
    rem Step 2 (cont): Set java path to just 'java'
    set "javaPath=java"
)

for /f tokens^=2-5^ delims^=.-_^" %%j in ('%javaPath% -fullversion 2^>^&1') do set "jver=%%j"
if %jver% LSS 11 (
  echo Java version is too low
  echo at least 11 is needed
  exit /b 1
)

rem Step 4: Run the java application
%javaPath% .\src\lab\oleksiienko\Important.java %*

:end
endlocal
