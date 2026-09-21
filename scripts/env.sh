#!/usr/bin/env bash
# Usage: source scripts/env.sh
# Points JAVA_HOME/PATH at JDK 21 for the current shell only (system variables are not touched).
# The system-level JAVA_HOME on this machine points to a non-existent JDK 8, so this is required.

export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
export PATH="$JAVA_HOME/bin:$PATH"

java -version
