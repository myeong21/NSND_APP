# 앱 내부 졸음·위치 기록

## 구현 범위와 기존 작업 보존

서버 `C:\Users\dudrj\NPNS`는 읽기만 했습니다. Git 커밋·push·discard는 하지 않았습니다.
시작 시의 미커밋 파일은 `C:\Users\dudrj\AppData\Local\Temp\nsnd-before-local-records-1ayx15xj`에 복사해 두었습니다.
기존 로그인 권한 완화, 공통 색상, 필터 배경 두 개와 내 위치 아이콘은 보존했습니다.
지도·웹 지도·수면데이터에는 기존 필터와 디자인을 유지하면서 아래 기능을 추가했습니다.

기존 루트 `drowsyguard.db`는 열어 쓰지 않습니다. 작업 전 SHA-256:
`706f30c94f55f30696a8cb2b7cdd262098670c19616f2559b95367b0fc0dee91`.

## 저장 흐름

`DetectionForegroundService`가 WebSocket 결과를 파싱하고 수신 시각과 최신 위치 스냅샷을 즉시 캡처합니다.
SQLite 쓰기는 별도 IO 코루틴에서 순서대로 수행하므로 위치 조회·DB 쓰기를 WebSocket 결과 처리에서 기다리지 않습니다.
홈의 BroadcastReceiver는 화면 표시용이며 저장을 담당하지 않습니다. 홈 밖에서도 서비스가 계속 저장합니다.

저장 위치는 기기의 앱 전용 `databases/nsnd_local_detection.db`입니다.
추가 의존성 없이 Android `SQLiteOpenHelper`를 사용하고 WAL을 켭니다. 앱 재실행으로 기록이 사라지지 않습니다.
앱 데이터 삭제·앱 제거는 별개이며 기록이 없어질 수 있습니다.

`local_events`에 아래 값을 저장합니다.

| 열 | 의미 |
|---|---|
| `id` | 앱에서 생성한 UUID. 서버 이벤트 ID가 아님 |
| `account_key` | 현재 서버 주소 + JWT의 사용자 ID. 사용자·서버별 분리 |
| `user_id`, `session_id` | 로그인 사용자 ID와 서버 운전 세션 ID |
| `received_at_ms` | 앱 결과 수신 시각, epoch milliseconds. 서버 감지 시각이 아님 |
| `stage` | 졸음 1~3단계 |
| `ear`, `prediction_score` | 서버의 `ear_value`, `pred_score`. 미제공이면 null |
| `latitude`, `longitude` | 감지 결과를 수신했을 때 유효했던 기기 위치. 없으면 둘 다 null |
| `location_measured_at_ms`, `accuracy_meters` | 위치 측정 시각과 정확도. 오래되거나 부정확한 위치의 메타데이터도 유지 |
| `location_status` | available / permission_denied / services_disabled / no_fix / stale / inaccurate |

별도 `transition_state`에 계정·운전 세션별 마지막 졸음 단계를 저장합니다.
양수 단계가 이전 단계와 다를 때만 이벤트를 삽입하고, 정상 0단계는 상태만 초기화합니다.
이벤트 삽입과 상태 갱신은 같은 SQLite 트랜잭션으로 처리합니다.
ping, 오류, 단계 없는 메시지, 범위 밖/소수 단계, JSON 오류는 상태를 변경하지 않습니다.

재연결과 서비스 재시작 후 동일 단계가 재전송되면 영구 체크포인트로 중복 삽입을 억제합니다.
서버 이벤트 ID·서버 감지 시각이 없어 완전한 중복 제거와 누락 없는 복원은 불가능합니다.
연결이 끊긴 동안 정상→동일 졸음 단계가 발생했다면 새 사건인지 알 수 없어 누락될 수 있습니다.
프로세스가 강제 종료되면 아직 IO 큐에서 처리하지 못한 마지막 결과는 유실될 수 있습니다.

## 위치 처리

서비스가 `LocationTracker`를 시작·유지합니다. 홈과 서비스는 소유자를 구분하고 마지막 소유자가 해제될 때 요청을 정리합니다.
위치는 3초 간격으로 갱신을 요청하며 정지 중에도 측정 시각을 갱신합니다. 실제 갱신 간격은 기기·OS·신호에 따라 달라집니다.
GPS를 먼저 조회하고 결과를 기다리느라 감지를 멈추는 구조가 아닙니다.

위치는 monotonic elapsed-realtime 기준 30초 이내, 정확도 100m 이내, 유효한 위경도인 경우만 사용합니다.
권한 거부·위치 서비스 꺼짐·신호 미수신·오래된 위치·정확도 부족이면 기록은 저장하되 좌표는 null입니다.
대략적 위치 권한만 허용했을 때 오차가 크면 좌표가 비어 있을 수 있습니다.
수신 시점에 위치가 없었던 과거 기록을 나중 위치로 채우지 않습니다.

실제폰의 위치와 에뮬레이터가 제공하는 가상 GPS를 그대로 사용합니다. 해외 좌표를 판교로 바꾸지 않습니다.
판교는 지도 초기 중심일 뿐 실제 내 위치를 뜻하지 않습니다. 현재 위치 조회 실패 시 내 위치 마커를 만들지 않습니다.
현재 위치 버튼은 캐시의 최신 여부를 검사하고 필요하면 5초 제한으로 새 위치를 요청합니다.
유효한 내 위치 마커는 감지 기록 새로고침으로 삭제하지 않으며, 오래되거나 권한/위치 서비스가 꺼지면 제거합니다.

서비스에 `specialUse|location` 타입을 선언하고 위치 권한이 있을 때만 location 타입으로 승격합니다.
Android의 실행 상태 제한으로 위치 타입을 사용할 수 없으면 위치 없이 감지는 계속합니다.
이 동작은 [Android 위치 foreground service 요구사항](https://developer.android.com/develop/background-work/services/fgs/service-types)을 따릅니다.
서비스 시작은 앱에서 운전/감지를 시작한 사용자 흐름으로 수행하며 별도 백그라운드 위치 권한은 추가하지 않았습니다.

## 지도·수면데이터

- 지도에서 `로컬 기록 · 기기 위치`와 `서버 기록 · 기존 저장 좌표`를 선택할 수 있습니다. 선택한 출처만 표시합니다.
- 로컬 전체/날짜 필터는 로컬 DB를 조회합니다. 서버 날짜별은 기존 날짜 API, 서버 전체는 운전 히스토리와 세션 상세 API로 조회합니다. `date="all"`을 보내지 않습니다.
- 날짜는 기기의 시간대에 따른 앱 수신 날짜이고, 범위는 해당 날짜 시작 포함/다음 날짜 시작 제외입니다.
- 지도 배지·목록은 선택한 출처의 건수입니다. 서버·로컬 건수는 합산하지 않습니다. 좌표 없는 기록도 목록에 나오며 지도 마커는 없습니다.
- 같은 좌표/가까운 위치의 마커를 누르면 겹친 기록 목록으로 UUID별 기록을 선택합니다.
- 상세에 앱 수신 시각, 졸음 단계, EAR, 예측 점수, 좌표/위치 없음, 위치 측정 시각·정확도, 세션 ID·기록 ID를 표시합니다.
- 수면데이터 지도와 상세 목록, 운전 세션 상세 위치도 같은 출처 선택을 제공합니다. 로컬 모드는 같은 로컬 DB, 서버 모드는 서버가 이미 저장한 원본 좌표를 사용합니다.
- 수면데이터 점수·차트·운전 히스토리는 서버 통계입니다. 로컬 건수와 합산하거나 서버 이벤트와 임의로 매칭하지 않습니다.
- 로컬 기록이 없는 과거 서버 기록의 좌표는 만들지 않습니다.
- 과거 서버 기록에 실제 좌표가 있으면 서버 모드에서 복원해 표시합니다. 로컬 이벤트로 복사하거나 서버 기록과 로컬 기록을 매칭하지 않습니다.
- 서버 API가 이벤트 ID·EAR·예측 점수·위치 측정 정보를 제공하지 않으므로 서버 행은 현재 응답 내 UI ID로 구분하고, 미제공 값은 상세에 명시합니다.
- 지도 확장은 `MainActivity.openMapTab()`으로 하단 지도 탭을 선택하여 동일한 탭 백스택을 사용합니다.
- 수면데이터에서 전체 지도를 열 때 선택 날짜·출처를 넘깁니다. 날짜·출처 선택은 탭 복원과 화면 재생성 때 유지합니다.
- 서버 주소가 바뀌면 로컬 계정 범위도 바뀝니다. 같은 회원이라도 서버 주소를 바꾸면 이전 주소로 저장한 기록이 자동 병합되지 않습니다.

카카오맵에는 기존 키 설정을 사용합니다. Android 키 해시/JavaScript 키와 등록 도메인이 맞아야 배경 지도가 표시됩니다.
현재 에뮬레이터에서 네이티브 지도는 `MapAuthException(401): Unauthorized`를 반환하고, 전체 지도는 웹 지도 대체 경로로 실행됩니다.
세션 상세의 네이티브 지도 인증이 실패하면 안내와 로컬 기록 목록에서 좌표를 확인할 수 있습니다.
해외 좌표의 카카오 지도 배경은 제한될 수 있지만 원본 좌표는 목록과 DB에 남습니다.
네이티브 전체 보기에는 [Kakao `fitMapPoints`](https://apis.map.kakao.com/android_v2/reference/com/kakao/vectormap/camera/CameraUpdateFactory.html)를 사용합니다.

## 실행·시연 순서

1. `C:\Users\dudrj\NSND_APP`에서 `.\gradlew.bat :app:assembleDebug`를 실행합니다.
2. `app\build\outputs\apk\debug\app-debug.apk`를 설치하고 로그인합니다.
3. 서버 주소를 실제 노트북 주소로 설정하고 기존 서버를 실행합니다. 서버 코드 변경은 필요 없습니다.
4. 앱에서 운전 세션 시작→감지 시작을 누르고 위치 권한과 기기 위치 서비스를 허용합니다.
5. 에뮬레이터 Extended Controls→Location에서 A를 설정하고 위치 갱신 후 정상→1단계를 발생시킵니다.
6. 홈에서 수면데이터/지도 탭으로 이동합니다. B로 GPS를 바꾸고 정상→1단계 또는 1→2단계를 발생시킵니다.
7. 지도 전체 기록과 해당 날짜에서 A/B를 확인합니다. 같은 B에서 2→3단계 등으로 추가 기록을 만들고 마커/기록 목록을 선택합니다.
   기존 9월 20일 더미 기록은 `서버 기록 · 기존 저장 좌표`를 선택하고 날짜를 `2026-09-20`으로 설정해 확인합니다.
8. 감지 종료 후 앱을 다시 열어 로컬 기록이 남는지 확인합니다. 로그아웃 후 다른 회원에게 이전 기록이 보이지 않는지 확인합니다.
9. 위치 권한을 거부하거나 위치 서비스를 끄고 감지합니다. 기록 목록에 위치 없음, 지도에는 해당 마커가 없어야 합니다.
10. 수면데이터→전체 지도→하단 수면데이터 탭을 누릅니다. Android 뒤로가기를 누르지 않아도 복귀해야 합니다.

실제 카메라의 단계 전환은 서버 모델 판단에 따릅니다. 이 기능은 노트북 위치가 아니라 결과를 받는 Android 기기의 위치를 기록합니다.

## 검증 명령과 범위

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :app:lintDebug
node tools/test_kakao_local_markers.cjs
```

`LocalDetectionStoreTest`는 실제 Android SQLite를 별도 테스트 DB 이름으로 열어 A/B 좌표, 겹친 UUID,
날짜 범위, 좌표 없음, 계정/서버 분리, DB 재열기와 체크포인트 유지, 메시지 파싱, 위치 신선도를 검사합니다.
`DetectionServiceIntegrationTest`는 에뮬레이터 내부 임시 WebSocket 서버로 실제 서비스를 검사합니다.
`gradle.properties`의 `android.injected.androidTest.leaveApksInstalledAfterRun=true`로 검사 후 앱을 유지합니다.
실제 NPNS 서버를 호출하지 않으며 별도 DB를 사용하고 임시 로그인 설정/위치 서비스 설정은 테스트 종료 시 복원합니다.
서비스 검증 중에는 위치 서비스를 꺼 위치 없는 저장을 확인하고, 화면 검증용 A/B는 별도 테스트 입력으로 넣습니다.
이 A/B를 실제 카카오 웹 지도의 마커 좌표로 확인하고, 겹친 기록 선택과 EAR 상세, 내일 날짜 필터의 빈 결과,
전체 기록 복원, 지도에서 하단 수면데이터 탭으로 복귀도 자동 UI 검사합니다.
실제 GPS·카메라·서버를 연결한 A→B 시연과 완전한 앱 프로세스 종료/재실행 검사는 자동 SQLite/서비스 검사와 구분해야 합니다.

웹 지도 검사는 Kakao 객체 대역을 사용합니다. 실제 Kakao 서버의 키 인증·배경 타일 표시를 검증하는 검사는 아닙니다.
자동 검사 실행 결과와 미확인 항목은 최종 결과에 별도로 명시합니다.

검증 명령은 로컬 기록 유지와 서버 과거 기록 복원, 좌표 없는 서버 행 제외, 출처별 건수 분리도 검사합니다.
린트 실행은 오류 25개로 실패했습니다. 오류 위치의 코드를 작업 전 백업/HEAD와 대조했고 모두 기존 코드에 있었습니다.
대표적으로 기존 뒤로가기 처리, `android:tint`, 테마의 API 호환성, 전화 하드웨어 선언 문제가 있습니다.
이번 작업에서 이들 기존 오류 전체를 정리하지는 않았습니다.

미확인 항목: 실제 노트북 카메라·서버와 에뮬레이터 가상 GPS A→B를 함께 연결한 시연,
실제폰 GPS와 OS 권한 거부/재허용, 앱 전체 프로세스 종료 뒤 재실행, 실제 두 회원의 서버 로그인 전환,
네이티브 지도 인증 성공 환경. DB 재열기·서비스 재시작·계정별 DB 조회는 위 자동 검사와 구분됩니다.

## 변경 파일

앱 동작 변경:
- `AndroidManifest.xml`: 위치 foreground service 타입.
- `data/session/SessionManager.kt`: JWT 사용자 ID/서버별 계정 키, 로그아웃·세션 종료 정리.
- `service/DetectionForegroundService.kt`: 메시지 검증·IO 저장·중복 체크포인트·위치 소유·종료 정리.
- `service/LocationTracker.kt`: 위치 측정 메타데이터·신선도/정확도·소유자 관리.
- `ui/home/HomeFragment.kt`: `ear_value` 필드 수정·권한 안내·리스너 정리.
- `MainActivity.kt`: 지도 확장과 하단 탭 탐색 통일.
- `ui/map/MapFragment.kt`, `assets/kakao_map.html`, `res/layout/fragment_map.xml`: 로컬 필터/마커/목록/상세·내 위치 개선.
- `ui/sleepdata/SleepDataFragment.kt`, `SessionDetailBottomSheetFragment.kt`, `SleepEventItem.kt`, `SleepEventAdapter.kt`:
  로컬 위치/목록/상세 연결, 서버 통계 분리.
- `res/layout/fragment_sleep_data.xml`, `bottom_sheet_session_detail.xml`: 출처·로컬 기록 버튼.

새 파일:
- `data/local/DetectionPolicy.kt`, `DetectionMessageParser.kt`, `LocalDetectionRecord.kt`, `LocalDetectionStore.kt`.
- `ui/common/LocalRecordDialogs.kt`.
- `ui/common/MapRecord.kt`, `MapRecordDialogs.kt`, `ServerMapRecords.kt`: 출처별 지도 행과 서버 원본 좌표 조회.
- `res/values/map_record_sources.xml`: 지도·수면데이터·세션 상세의 출처 선택.
- `src/test/.../DetectionPolicyTest.kt`.
- `src/androidTest/.../LocalDetectionStoreTest.kt`, `DetectionServiceIntegrationTest.kt`.
- `src/androidTest/.../ServerMapRecordsTest.kt`: 서버 원본 좌표와 로컬 데이터 분리 검사.
- `tools/test_kakao_local_markers.cjs`, 이 문서.
- `.gitignore`에 빌드가 생성하는 `.kotlin/`을 제외하도록 한 줄 추가.
- `gradle.properties`: 에뮬레이터 검사 후 앱 자동 제거 방지.

기존 미커밋 `LoginActivity.kt`, `colors.xml`, 필터 배경 두 개, 내 위치 아이콘, 루트 DB는 이번 작업으로 변경하지 않았습니다.
