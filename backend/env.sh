#!/usr/bin/env zsh

if [[ -n "${BASH_SOURCE[0]:-}" ]]; then
  SCRIPT_PATH="${BASH_SOURCE[0]}"
elif [[ -n "${ZSH_VERSION:-}" ]]; then
  SCRIPT_PATH="${(%):-%x}"
else
  SCRIPT_PATH="$0"
fi

SCRIPT_DIR="$(cd "$(dirname "$SCRIPT_PATH")" && pwd)"
JAVA17_HOME="${JAVA17_HOME:-}"

if [[ -z "$JAVA17_HOME" && -d "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" ]]; then
  JAVA17_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
fi

if [[ -z "$JAVA17_HOME" && -d "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" ]]; then
  JAVA17_HOME="/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
fi

if [[ -n "$JAVA17_HOME" ]]; then
  export JAVA_HOME="$JAVA17_HOME"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

export MAVEN_OPTS="${MAVEN_OPTS:-} -Dmaven.repo.local=$SCRIPT_DIR/.m2/repository"
