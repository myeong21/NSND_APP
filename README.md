# NSND Android 개발 환경

Kotlin 기반 Android 앱입니다. 백엔드 코드는 이 저장소에 포함되어 있지 않습니다.

## 현재 개발 환경

- JDK 21: `.local/jdk-21` (프로젝트 전용 설치, Git 제외)
- Android SDK: `/home/david/Android/Sdk` (`local.properties`)
- SDK Platform 36, Build Tools 35.0.0 (AGP 기본값), 36.0.0
- Gradle 8.13 / Android Gradle Plugin 8.13.2 (저장소에 고정)
- Android 기기: API 26 이상

Android Studio에 포함된 Java 25 대신 JDK 21로 Gradle을 실행합니다.
Android Studio는 `.gradle/config.properties`의 `java.home`을 사용하도록 구성되어 있습니다.
다른 PC에서는 JDK 21 및 SDK 36을 설치하고 `local.properties`의 `sdk.dir`과
Android Studio Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK를 해당 경로로 지정하세요.
CLI는 `.local/jdk-21`을 우선 사용하며, 없으면 `JAVA_HOME`을 사용합니다.

## 빌드와 검증

```bash
./tools/gradle.sh :app:assembleDebug :app:testDebugUnitTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`
단위 테스트 결과: `app/build/reports/tests/testDebugUnitTest/index.html`

## 실행 및 디버깅

1. Android Studio에서 프로젝트를 열고 Gradle Sync를 완료합니다.
2. Build Variants에서 `app`의 `debug`를 선택합니다.
3. USB 디버깅을 허용한 기기를 연결하거나 Device Manager에서 에뮬레이터를 실행합니다.
4. 실행 구성 `app`과 대상 기기를 선택한 뒤 Debug를 누릅니다. 로그인 화면이 시작됩니다.
5. 원하는 Kotlin 코드에 중단점을 설정합니다. CLI로 실행했다면 Attach Debugger to Android Process에서 `com.jsm.nsnd`를 선택합니다.

CLI에서도 다음 명령을 사용할 수 있습니다.

```bash
./tools/run-android.sh devices
./tools/run-android.sh emulator                    # 첫 번째 AVD 실행
./tools/run-android.sh emulator Medium_Phone_API_37.0
./tools/run-android.sh run                         # 빌드, 설치, 로그인 화면 실행
# 여러 기기가 연결되어 있다면:
ANDROID_SERIAL=emulator-5554 ./tools/run-android.sh run
```

Linux 에뮬레이터의 하드웨어 가속에는 `/dev/kvm` 접근이 필요합니다.
이 장치가 없는 환경에서는 실제 Android 기기를 연결하거나 KVM이 지원되는 호스트에서 실행하세요.
기기 연결 후 계측 테스트는 `./tools/gradle.sh :app:connectedDebugAndroidTest`로 실행합니다.

현재 카카오 지도 네이티브 라이브러리는 ARM ABI만 포함합니다.
x86/x86_64 에뮬레이터에서는 지도 네이티브 기능에 제약이 있으므로 지도 검증에는 ARM64 실제 기기를 사용하세요.

## 백엔드 연결

기본 서버는 `http://10.0.2.2:8000/`입니다. Android 에뮬레이터에서 호스트 PC에 접근하는 주소입니다.
별도 백엔드를 호스트의 `0.0.0.0:8000`에서 실행해야 로그인 등 서버 기능을 사용할 수 있습니다.
로그인 화면의 서버 주소 설정에서 호스트 이름 또는 IP를 변경할 수 있습니다.
실제 기기에서는 같은 네트워크의 PC IP를 입력하거나 `adb reverse tcp:8000 tcp:8000` 실행 후
서버 주소를 `127.0.0.1`로 지정하세요. 주소에는 프로토콜과 포트를 넣지 않습니다.
카카오 지도 사용 시에는 해당 카카오 앱에 로컬 디버그 서명 키 해시 및 패키지 `com.jsm.nsnd` 등록이 필요합니다.
