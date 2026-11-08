#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -x "$PROJECT_ROOT/.local/jdk-21/bin/java" ]]; then
    export JAVA_HOME="$PROJECT_ROOT/.local/jdk-21"
elif [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
    echo 'JDK 21을 설치한 뒤 JAVA_HOME을 지정하세요. README.md 참고.' >&2
    exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"
cd "$PROJECT_ROOT"
exec bash ./gradlew "$@"
