#!/bin/sh

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -z "$JAVA_HOME" ]; then
    for candidate in \
        "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home" \
        "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home" \
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
        "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"; do
        if [ -x "$candidate/bin/java" ]; then
            JAVA_HOME="$candidate"
            export JAVA_HOME
            break
        fi
    done
fi

if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD=java
fi

exec "$JAVACMD" -Dorg.gradle.appname=gradlew -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
