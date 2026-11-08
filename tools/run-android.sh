#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_ROOT" && -f "$PROJECT_ROOT/local.properties" ]]; then
    SDK_ROOT="$(sed -n 's/^sdk.dir=//p' "$PROJECT_ROOT/local.properties" | head -n 1)"
fi
SDK_ROOT="${SDK_ROOT:-$HOME/Android/Sdk}"
ADB="$SDK_ROOT/platform-tools/adb"
if [[ ! -x "$ADB" ]]; then
    echo "adb를 찾을 수 없습니다: $ADB" >&2
    exit 1
fi
case "${1:-run}" in
    emulator)
        EMULATOR="$SDK_ROOT/emulator/emulator"
        AVD="${2:-$("$EMULATOR" -list-avds | head -n 1)}"
        [[ -n "$AVD" ]] || { echo 'Android Studio Device Manager에서 AVD를 생성하세요.' >&2; exit 1; }
        exec "$EMULATOR" -avd "$AVD"
        ;;
    devices) exec "$ADB" devices -l ;;
    run)
        # ANDROID_SERIAL로 여러 기기 중 대상 기기를 선택할 수 있습니다.
        "$ADB" get-state >/dev/null
        bash "$PROJECT_ROOT/tools/gradle.sh" :app:assembleDebug
        "$ADB" install -r "$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
        "$ADB" shell am start -W -n com.jsm.nsnd/.ui.auth.LoginActivity
        ;;
    *) echo "사용법: $0 {run|devices|emulator [AVD 이름]}" >&2; exit 1 ;;
esac
