#!/usr/bin/env bash
# User-local Android toolchain installed for StorePrice stage 0.
# Usage: source scripts/android-env.sh

if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(find "$HOME/.local/opt" -maxdepth 1 -type d -name 'jdk-17*' | sort | head -n 1)"
  export JAVA_HOME
fi

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

GRADLE_HOME="${GRADLE_HOME:-$(find "$HOME/.local/opt" -maxdepth 1 -type d -name 'gradle-*' | sort -V | tail -n 1)}"
if [ -n "$GRADLE_HOME" ]; then
  export GRADLE_HOME
  export PATH="$GRADLE_HOME/bin:$PATH"
fi

export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
