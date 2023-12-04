#!/bin/bash

# Step 1: Check if 'java' is defined in the path
if ! command -v java &> /dev/null; then
    echo "'java' is not found in the path."

    # Step 2: Check if JAVA_HOME is defined
    if [ -z "$JAVA_HOME" ]; then
        echo "JAVA_HOME is not defined. Please set JAVA_HOME environment variable."
        exit 1
    fi

    # Step 2 (cont): Set java path to JAVA_HOME/bin/java
    javaPath="$JAVA_HOME/bin/java"
else
    # Step 2 (cont): Set java path to just 'java'
    javaPath="java"
fi

# Step 3: Check if java version is at least 11
javaVersion=$("$javaPath" -version 2>&1 | awk -F '"' '/version/ {print $2}')
majorVersion=$(echo "$javaVersion" | cut -d'.' -f1)
if [ "$majorVersion" -lt 11 ]; then
    echo "Java version 11 or higher is required."
    exit 1
fi

# Step 4: Run the java application
"$javaPath" ./src/lab/oleksiienko/Important.java "$@"
