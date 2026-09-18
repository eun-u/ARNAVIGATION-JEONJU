# NaVi 프로젝트 마스터 계획과 현재 상태

마지막 갱신: 2026-09-18 19:18 KST
현재 활성 작업: M1-VIS-2 — 녹화 frame sequence에서 A/B와 stale fallback을 반복 검증하는 replay 준비

이 문서는 NaVi 개발의 **단일 작업 기준**이다. 날짜가 붙은 체크포인트, 초기 제안서, 개별 모듈 문서와 상태가 충돌하면 이 문서의 `현재 상태`, `결정 사항`, `바로 다음 작업`을 우선한다. 세부 설계와 원시 결과는 링크된 문서에 남기되, 작업을 마칠 때마다 이 문서에 완료 근거와 다음 시작점을 반영한다.

## 작업 종료 시 갱신 규칙

각 작업을 종료하기 전에 반드시 다음을 수행한다.

1. 상태 표를 `미착수 / 진행 중 / 보류 / 완료` 중 하나로 갱신한다.
2. 실행한 테스트, 실기기 결과, 생성한 보고서의 경로를 기록한다.
3. 실패·편차·미해결 위험을 숨기지 않고 `남은 문제`에 기록한다.
4. 다음 세션이 설명 없이 시작할 수 있도록 `바로 다음 작업`을 한 개로 고정한다.
5. 다른 채팅 소유 파일과 작업 경계를 다시 확인한다.

## 고정 결정 사항

- AR과 AI는 별도 모듈로 유지한다.
- `:feature:ar-navigation`이 ARCore 카메라 세션을 단독 소유하고 timestamp가 있는 frame/pose/depth를 공급한다.
- `:feature:ai-perception`은 탐지·분할·추적 결과만 만들며 AR 렌더링, Edge 선택, Graph 변경을 하지 않는다.
- `:feature:guidance-fusion`은 동일 timestamp 결과를 결합하지만 공용 Graph를 수정하지 않는다.
- 현재 기하 기반 AR과 AI 보정 AR은 대체하지 않고 병렬 실행한다. 현재 AR은 항상 기준선·fallback이고 AI 보정은 먼저 shadow 출력만 만든다.
- AR 렌더링은 연속 실행하고 AI는 별도 bounded worker에서 최대 5~10Hz로 최신 frame만 처리한다. stale·저신뢰 mask는 사용하지 않는다.
- 결정론적 Rule/Cost Engine이 통과 가능성을 판정하고 RouteEngine이 경로를 계산한다.
- LLM은 계산된 후보의 설명·비교·제한된 프로필 변환만 담당한다.
- 자동 관측은 `pending`, `verified=false`, session-local 또는 shadow mode를 유지한다.
- 안양 현장 방문은 현재 계획에 없다. 안양 자료는 공모전 데모·공간자료 분석·synthetic 시나리오에만 사용한다.
- 위치 기반 AR 검증은 사용자 생활권의 작은 임시 OSM 보행망에서 수행한다. 거리 영상은 A/B 시각화와 정적 보도 분할 시험에 사용하지만 실제 AR tracking·GPS 정확도의 Ground Truth가 아니다.

## 전체 단계 상태

| 단계 | 상태 | 현재 근거 | 다음 조건 |
|---|---|---|---|
| M0 계약·모듈 골격 | 완료 | `guidance-contract`, `ar-navigation`, `ai-perception`, `guidance-fusion` 분리 및 빌드·계약 테스트 | 경계 변경 시 회귀 테스트 |
| M1 AR 기술 스파이크 | 진행 중 | 기존 AR 실험과 M1-VIS-1 정적 A/B·fallback·JSON/SVG harness 완료 | M1-VIS-2 녹화 replay 후 SM-S911N shadow |
| E2E-WC 휠체어 가정 재탐색 실증 | 보류 | 단독 에뮬레이터에서 A 130.7m → session-local 차단 → B 153.5m → 저정확도 거부 → 3회·2초 자동 도착 폐루프 통과 | A·B 사람 사전 점검 뒤에만 현장 1회 실행 |
| M2 AI device-free | 별도 채팅 소유 | 이 채팅에서는 AI importer/tracker/segmentation/evaluation 파일을 수정하지 않음 | 별도 채팅 결과를 계약으로 인계 |
| 공간데이터·Graph 후보 | 자동화 완료·사람 검수 대기 | 247개 후보/196개 Edge, 요청 한정 시뮬레이션 17개, 근거 전용 230개, 정사영상 참조 247/247 | 계단 후보 검수·정사영상 RMSE 확보 전 Graph 승격 금지 |
| M3 공간 융합·Map Matching | 계획 확정·미착수 | 공통 계약과 기존 backend 경로 엔진, A/B 병렬·stale fallback 계획 존재 | M1-VIS frame 계약과 M2 perception 출력 준비 |
| M4 세션 룰·재탐색 | 기반 존재 | backend에 deterministic constraint, Dijkstra, session temporary block 존재 | M3 observation을 shadow mode로 연결 |
| M5 LLM 설명·제안 | 미착수 | 역할과 금지 경계만 확정 | M4 결정론적 결과 안정화 |
| M6 검증 | 계획 수정 | 안양 방문 계획 폐기 | 생활권 로컬 OSM 시험과 별도 사람 검수 증거 사용 |

## M1 실험 현황

### 완료

- Samsung SM-S911N, Android 16에서 ARCore 세션과 `DepthMode.AUTOMATIC` 확인.
- ARCore MP4와 telemetry CSV 녹화, 전체 Playback, live camera 복귀 확인.
- 20분 live AR soak: 1,200.2초, crash 0, process death 0, fatal exception 0.
- soak 결과: tracking 210/225, degraded 15/225, Depth active 209/225, 관측 loss 최대 1회, 복구 866ms.
- 성능 결과: frame 평균 2.09ms/p95 6.198ms, 배터리 최고 42.3°C, thermal status 최고 2.
- 동일 dataset Playback 5회 모두 `PLAYBACK_FINISHED`; 프로세스 종료와 fatal error 없음.
- 화면 자동 꺼짐 가설은 `keepScreenOn=true`, display ON 로그를 근거로 배제.

### 실험 순서에서 누락된 항목

원래 합의한 순서는 `계측 보강 → 반복 Playback → 원인별 통제 실험`이었다. 실제로는 계측 보강 전에 Playback 5회를 먼저 실행했다. 따라서 5회 결과는 재생 안정성을 입증하지만 loss 원인을 분류하지 못한다.

현재 telemetry는 ARCore `trackingFailureReason`을 UI message로만 전달한다. CSV와 진단 로그에는 lifecycle, 화면 상태, session generation, 전환 원인이 구조화되어 있지 않으며 View 재생성 시 카운터 수명도 명확하지 않다.

## 완료 작업: M1-OBS 추적 손실 관측성 보강

완료 범위:

1. telemetry와 CSV에 다음 필드를 추가했다.
   - `tracking_failure_reason`
   - `lifecycle_state`
   - `display_interactive`
   - `session_generation`
   - `dataset_mode`
   - `transition_reason`
   - `expected_session_transition`
   - 실제 tracking loss와 예상된 session-transition loss의 분리 카운터
2. 카운터와 session generation이 View 재생성을 넘어 앱 프로세스 동안 유지되게 했다.
3. 상태 전환 로그와 CSV escaping/계약 단위 테스트를 추가했다.
4. SM-S911N에 빌드·설치하고 Playback → live 1회로 새 로그 계약을 확인했다.
5. 다음 통제 실험을 각각 1회 이상 수행했다.
   - 화면 잠금 → 해제
   - 저조도 또는 카메라 가림
   - 급격한 기기 움직임
6. 원인, lifecycle, 화면 상태, 실제/예상 loss, 복구 시간을 아래 표로 기록했다.

20분 soak와 Playback 5회는 반복하지 않는다. AR session/rendering 코드 또는 기준 기기·빌드 유형이 바뀔 때만 soak를 다시 수행한다.

### M1-OBS 완료 기록 — 2026-09-18

- 구현 완료: `tracking_failure_reason`, lifecycle, display interactive, session generation, dataset mode, transition reason, expected transition, 실제/예상 loss 분리.
- process-scoped diagnostics store로 View와 ARCore session 재생성 사이에 generation과 카운터를 유지한다.
- CSV에는 기존 열을 보존하고 구조화 진단 열을 뒤에 추가했다.
- AR 단위 테스트 9개 통과, AR lint 통과, debug APK 빌드 통과, `git diff --check` 오류 없음.
- SM-S911N에 최신 APK를 설치했다.
- Playback → live 1회 검증:
  - Playback 시작 loss: expected 1, unexpected 0, 복구 669ms.
  - live 복귀 loss: expected 2 누적, unexpected 0, 복구 1,618ms.
  - session generation `1 → 2`, fatal error 없음.
- 통제 실험 결과:

| 자극 | 관측 결과 | 실제/예상 loss | 복구 | 판정 |
|---|---|---|---|---|
| 화면 잠금 → 해제 | `interactive=false`, `PAUSED → STOPPED → STARTED → RESUMED`; 복귀 중 `INSUFFICIENT_FEATURES → EXCESSIVE_MOTION → NONE` | unexpected 0 / expected 누적 3 | 4,938ms | 정상 session 전환으로 분리 성공 |
| 카메라 완전 가림 약 8초 | 계속 `TRACKING`, Depth active | unexpected 0 / expected 변화 없음 | 손실 없음 | 이 자극에서는 손실 미발생 |
| 카메라 가림 + 느린 좌우 회전 약 15초 | 계속 `TRACKING`, Depth active | unexpected 0 / expected 변화 없음 | 손실 없음 | 관성 추적 유지 |
| 급격한 좌우·상하 움직임 약 5초 | 계속 `TRACKING`, Depth active | unexpected 0 / expected 변화 없음 | 손실 없음 | 이 자극에서는 손실 미발생 |

- 잠금 해제 뒤 별도의 앱 background/foreground 전환도 expected loss 누적 4, unexpected 0, 3,470ms 복구로 분류됐다.
- 모든 통제 실험 뒤 앱 PID 2258이 유지됐고 fatal exception은 없었다.
- 실험 직후 ARCore 네이티브 로그에 `spherical_rectifier.cc:161`과 `FEATURE_DSP_CAM_IMU_DESYNC` 경고가 반복됐지만 앱 상태는 `TRACKING`, Depth active였다. 제품 오류인지 기기/ARCore 진단 로그인지 성능 측정과 함께 분리 확인한다.

## 완료 작업: M1-PERF debug/benchmark 성능 기준선

- soak 스크립트의 CPU를 누적 `dumpsys cpuinfo`에서 `top` 순간 표본으로 교정하고 새 구조화 telemetry 계약에 맞췄다.
- release 설정을 상속하는 로컬 전용 non-debuggable `benchmark` 빌드 타입을 추가했다. production `release` 서명 정책은 변경하지 않았다.
- 동일 실내 장면의 1분 비교에서 두 빌드 모두 tracking/Depth 전 표본 정상, 실제 loss 0, fatal/process death 0이었다.
- debug → benchmark 결과:
  - CPU 평균 `99.829% → 70.829%` (29.0% 상대 감소)
  - frame 평균 `2.819ms → 1.647ms` (41.6% 상대 감소)
  - frame p95 `6.249ms → 3.179ms` (49.1% 상대 감소)
  - 평균 PSS `423,581KB → 354,851KB` (16.2% 상대 감소)
- benchmark 스레드 6표본에서 `ms_late_stage`가 평균 33.92%로 가장 높아 ARCore motion-stereo/Depth가 주 부하라는 정황을 확인했다.
- 상세 결과: `docs/m1_ar_performance_baseline_20260918.md`

## 완료 작업: M1-ALIGN 전북대 내부 정합 경로 확정

- 전북대학교 캠퍼스 전체 보행망을 직선성, 원 보행선 양끝 여유, 차량·계단·횡단 이격, 건물 이격과 캠퍼스 내부 깊이로 선별해 106 학생군사교육단 남측 녹지 보행로를 활성 PoC 경로로 확정했다. 기존 정문 주변 구간은 활성 시험에서 제외한다.
- `scripts/prepare_local_ar_route.py`가 최신 OSM 보행망을 조회하고 계단·횡단·실내·사유지·보행 금지 way를 제외한 뒤 20~30m 구간을 선택한다. `--expected-osm-way-id`를 추가해 재생성 시 다른 way로의 무음 변경을 실패 처리한다.
- 현재 선택 결과는 OSM way `471373639`, `highway=footway`, 길이 `25.0m`, 방위 `93.09°`다. 원 보행선은 `75.031m`이고 시험 구간 전후 여유는 각각 약 `25m`다.
- 정확한 좌표와 원본 GraphML·route GeoJSON·manifest는 `data/runtime/local-field-tests/jbnu-jeonju-campus-poc/`에 있으며 `.gitignore`로 제외했다.
- 접근성 값은 모두 `unknown`, `verified=false`, `field_test_only=true`, `shared_graph_mutation_allowed=false`다. OSM 형상만 사용하며 휠체어 통행 가능성을 주장하지 않는다.
- 전용 backend 계약 검증: Graph `2 nodes / 1 edge`, pending observation `0`, 일반/접근 가능 경로 모두 `25.0m`, 미검증 Edge `1`.
- 새 내부 Graph의 backend 계약 검증에서 Graph `2 nodes / 1 edge`, pending observation `0`, 일반/접근 가능 경로 모두 `25.0m`, 미검증 Edge `1`을 확인했다.
- 회귀 검증: backend 전체 `56 passed`, Android `:app:testDebugUnitTest :app:assembleDebug` 성공, 새 로컬 runtime 산출물의 Git 제외 확인.
- 검증 시점 ADB에는 에뮬레이터만 있고 SM-S911N이 연결되지 않아 새 장소명의 실기기 화면 확인은 대기 상태다. 실제 리본 정합 정확도도 현장 3회 전까지 미검증이다.
- 선정 근거: `docs/m1_jbnu_route_selection.md`
- 상세 실행 절차: `docs/m1_local_field_route_jbnu.md`

## 완료 작업: E2E-WC 전북대 재탐색 경로 준비

- 25m 단일 Edge는 AR 정합만 검증하며 차단 시 대체 경로가 없다는 한계를 계획에 반영했다.
- 같은 106 학생군사교육단 남측 현장에서 약 `51.2m` 공통 접근 뒤 분기하고 목적지에서 재합류하는 별도 로컬 Graph를 만들었다.
- 휠체어 프로필 초기 경로 A는 `130.7m`, 지정 Edge `LOCAL_OSM_0c2997b56763`을 현재 세션에서만 차단한 경로 B는 `153.5m`다. `route_affected=true`, `route_changed=true`이며 원본 Edge는 `blocked=false`로 유지된다.
- `scripts/prepare_local_reroute_route.py`는 예상 primary/alternate node path와 OSM way ID가 달라지면 생성에 실패한다.
- 생성 Graph는 `8 nodes / 8 edges`, pending observation `0`이며 모든 접근성 값은 `unknown`, `verified=false`, `field_test_only=true`다.
- 원본 GraphML, route GeoJSON, manifest는 Git 제외 경로 `data/runtime/local-field-tests/jbnu-jeonju-e2e-wheelchair/`에 생성했다.
- 새 코드 계약 테스트 3개, 기존 정합 생성기 테스트 4개와 backend 전체 회귀 `60 passed`를 확인했다. 두 생성기 `py_compile`과 `git diff --check`도 통과했다.
- 경로 A·B의 폭·경사·턱·표면·차량 동선과 실제 휠체어 통행 가능성은 현장 사람 점검 전까지 미검증이다.
- 상세 선정·실행 계획: `docs/e2e_wc_jbnu_route.md`

## 완료 작업: E2E-WC Android 현재 선분·자동 도착 gate

- 2D fallback은 위치 정확도가 `30m` 이하일 때 전체 geometry 첫 선분 대신 사용자와 `30m` 이내인 가장 가까운 forward segment의 방위를 사용한다.
- 사용자가 회전 꼭짓점·재탐색 분기점에 정확히 있을 때는 지나온 선분이 아니라 다음 선분을 우선하도록 tie-break를 고정했다.
- `NaviSessionStore.applyReroute()`가 경로 B geometry를 적용하면 같은 공통 함수가 분기점에서 B 방향을 즉시 반환하는 앱 단위 테스트를 추가했다.
- 도착 gate는 경로 geometry 끝점을 기준으로 거리 `10m` 이내, 위치 정확도 `15m` 이하인 서로 다른 관측 `3회`가 최소 `2초` 연속될 때만 성립한다. timestamp 중복·역전, 정확도 저하, 반경 이탈은 누적을 진행시키지 않거나 초기화한다.
- 수동 `종료 → 도착` 연결은 제거했다. gate가 성립해야 `NaviApp`이 도착 화면으로 이동하며, 안내 화면에는 판정 대기·정확도 부족·남은 거리·연속 확인 횟수를 표시한다.
- 활성 frontend 안내 화면에도 같은 현재 선분 계산과 도착 상태를 연결했다. 카메라 화면의 정적 corridor는 실제 `CameraHud`로 교체해 ARCore 3D 리본과 2D fallback을 사용하며, 수동 `종료`는 세션을 지우고 홈으로 돌아갈 뿐 도착 성공을 만들지 않는다.
- 실제 작업공간에서 `:core:guidance-contract:test` `13 tests / 0 failures`, `:app:testDebugUnitTest` `12 tests / 0 failures`, `:app:assembleDebug`를 함께 통과했다.
- 2026-09-18 에뮬레이터 합성 위치 폐루프를 시도해 전용 backend의 Graph `8 nodes / 8 edges`와 경로 A `130.7m` 로딩까지 확인했다. 그러나 같은 에뮬레이터를 다른 작업이 반복 설치·전경 전환 중이어서 별도 `.e2e` 앱의 한 세션 UI 증거를 신뢰성 있게 남길 수 없었다. 이 시도는 성공으로 세지 않으며 부분 DB는 Git 제외 경로 `data/runtime/local-field-tests/jbnu-jeonju-e2e-wheelchair/navi-e2e-emulator-20260918-1745.db`에 남겼다.

## 완료 작업: E2E-WC 합성 Android 폐루프 — 2026-09-18

- 다른 화면 작업이 사용하는 `emulator-5554`와 reverse `tcp:8001 → tcp:8001`을 건드리지 않고 임시 단독 `emulator-5556`과 backend `8002`를 사용했다.
- 완료 세션 `9393bae7-34bb-4058-9022-efcd0b70a735`에서 경로 A `130.7m`를 로드한 뒤 `LOCAL_OSM_0c2997b56763`만 session-local로 차단해 경로 B `153.5m`로 전환했다. 증가량은 `22.8m / 17.44%`다.
- 분기점에서 2D 안내가 A의 오른쪽 방향에서 B의 왼쪽 방향으로 바뀌었고, B geometry는 차단 Edge를 포함하지 않았다.
- 목적지의 정확도 `30m` 위치는 `정확도 부족`으로 거부했다. 이후 정확도 `5m`인 서로 다른 위치 3개를 총 2초 넘게 주입했을 때만 도착 화면으로 자동 전환됐다.
- 원본 Edge는 계속 `blocked=false`, `verified=false`였고 Graph revision은 전후 모두 `0`이다. 완료 세션 후보 `MOB_3C690B2E8C7D`도 `pending`, `verified=false`로만 저장됐다.
- ARCore와 Play Store가 없는 환경에서 자동 설치 화면을 호출해 앱이 종료되는 문제를 발견했다. 자동 설치 호출을 제거하고 availability를 먼저 판정해 CameraX/2D로 강등하도록 수정했다. 후면 카메라가 없는 환경도 정적 2D 배경으로 처리한다.
- 수정 APK 재시험에서 앱 PID가 유지되고 `130m`, `2D 대체 안내`가 표시됐으며 fatal exception과 CameraX 재시도 로그가 없었다.
- 회귀 검증: backend `60 tests`, 공통 계약 `13 tests`, AR `17 tests`, AI `28 tests`, app `20 tests` 전부 통과. debug·androidTest APK와 app·AR lint도 통과했다.
- 증거: `data/runtime/local-field-tests/jbnu-jeonju-e2e-wheelchair/evidence/20260918-1822/result.json`과 같은 폴더의 화면 4장. 이 경로는 Git 제외이며 결과 전체가 `verified=false`, `field_verified=false`다.
- 현재 frontend 경로 결과·재탐색·도착 화면 일부에는 실데이터와 별개인 안양/정적 데모 문구와 수치가 남아 있다. 화면 작업 소유 채팅에서 session 데이터로 교체한 뒤 시각 회귀를 다시 수행한다.
- Kakao JavaScript/REST 키는 루트 `.env`에만 저장했고 `.gitignore` 적용을 확인했다. 현재 Android 지도는 계속 MapLibre/OSM이며, 두 키를 Android 네이티브 지도 키로 오해해 연결하지 않는다.

## M1-VIS AR·AI 보정 병렬 비교

- 비교 A `GEOMETRY_BASELINE`: 현재 GPS·heading·ARCore pose/depth 기반 경로 리본을 그대로 유지한다.
- 비교 B `AI_ASSISTED_SHADOW`: 같은 frame에서 오픈소스 보도 segmentation mask를 얻어 리본의 횡방향 위치만 제한적으로 보정한다.
- A는 항상 사용자 안내와 fallback을 담당한다. B는 정량 검증 전까지 side-by-side/debug 출력만 만들며 경로·재탐색·Graph를 변경하지 않는다.
- 정적 거리 영상 → 녹화 replay → SM-S911N shadow → 전북대 내부 25m 현장 3회 순서로 같은 입력의 A/B를 비교한다.
- 공통 지표는 sidewalk IoU/Dice/recall, 리본 보도 내부 비율, 횡오차·흔들림, inference p50/p95, frame drop, CPU/PSS/온도, tracking loss다.
- 현재 AR benchmark CPU 평균이 `70.829%`이므로 AI를 AR과 같은 FPS로 실행하지 않는다. 5Hz부터 시작해 성능 여유가 확인될 때만 10Hz로 올린다.
- M1/AR 작업은 기준 리본과 비교 harness를 담당하고, 오픈소스 모델·mask 평가는 별도 M2 채팅이 담당하며, 실시간 결합은 M3 `guidance-fusion`에서 수행한다.
- 상세 계획: `docs/ar_ai_parallel_alignment_spike.md`

### M1-VIS-1 완료 — 정적 frame A/B

- `scripts/run_m1_vis_comparison.py`가 같은 pixel 좌표계의 A centerline과 sidewalk mask를 받아 제한 보정 B를 만들고 JSON·SVG를 생성한다.
- confidence `0.70` 이상, mask age `300ms` 이하, frame stamp·크기 일치를 Gate로 고정했다. 실패 시 B는 A와 동일하며 사용자 안내는 항상 A다.
- synthetic golden fixture에서 A/B 정합 metric과 최대 횡보정 상한을 재현했고 stale `301ms`, confidence `0.699`, frame/timestamp/크기 불일치, 빈 mask fallback을 검증했다.
- 단일 synthetic frame의 결과는 `verified=false`, `field_verified=false`이며 실제 AI 정확도·GPS/AR 정합·현장 개선을 의미하지 않는다.
- 회귀 검증: Python `73 tests`, 공통 계약 `13`, AR `17`, AI `28`, app `20` 전부 통과. debug·androidTest APK와 app·AR lint도 통과했다.
- 생성 결과는 Git 제외 경로 `artifacts/m1-vis/synthetic-golden/`에 저장된다.

## 완료 작업: 공간데이터 Graph 후보와 Android 검토 화면

- 수치지형도·DEM·정사영상·정밀도로지도 평가 결과를 기존 안양 Graph에 직접 병합하지 않고 read-only 후보 bundle로 분리했다.
- 후보는 총 247개/196개 Edge다.
  - 승인 검토 가능한 계단 속성 제안 5개
  - 90m DEM 기반 `approval_eligible=false` 경사 민감도 진단 12개
  - 보행공간·횡단시설·연석 근거 전용 230개
- Android 후보 화면은 `영향 시험 / 보행공간 / 횡단시설 / 연석` 레이어를 분리하고, 시뮬레이션 가능한 17개만 요청 한정 overlay로 계산한다.
- 전 후보 247개에 정사영상 도엽·pixel QA 참조 305개를 연결했다. 독립 기준점 RMSE가 없으므로 geometry 자동 보정과 Graph 반영은 금지한다.
- DEM 후보 `GEC-CE1DCD49F1BE0C70` 실서버 검증에서 `171.3m → 361.2m`, `+189.9m` 우회를 확인했다. 호출 전후 Graph SHA-256, SQLite revision, pending observation 수는 동일했다.
- 기준 Graph는 504 Node/723 Edge이며 평가 전후 SHA-256은 `b6c746a47c80d516bc506473335e0177eade52d82b8b635d1cb0f8ce2d9cac78`로 동일하다.
- 전체 공간평가는 pass 176/warning 7/hold 1/fail 0이다. hold는 정사영상 독립 기준점 RMSE 미확보를 명시한 의도된 보류다.
- 최종 회귀 검증: backend 56개, Android app 11개, AR 모듈 9개 단위 테스트 통과. app·AR lint, debug·benchmark·androidTest APK 빌드 통과.
- 상세 결과: `docs/graph_enrichment_candidate_report.md`, `docs/spatial_data_evaluation_report.md`, `docs/android_field_test.md`

## 바로 다음 작업

### M1-VIS-2: 녹화 replay A/B 비교

1. 기존 ARCore MP4·telemetry에서 평가 frame sequence를 만들되 M2 importer·모델 파일은 수정하지 않는다.
2. frame별 A centerline과 외부 mask 결과를 M1-VIS-1 입력 계약으로 변환하고 동일 frame stamp만 결합한다.
3. frame별 정합 metric에 반복 구간의 jitter, fallback 횟수·복구 시간을 추가하고 processing time을 제외한 반복 결과가 동일한지 확인한다.
4. 결과는 계속 `shadow`, `verified=false`, `field_verified=false`이며 앱 안내·재탐색·Graph에 연결하지 않는다.

`M1-ALIGN` 25m 현장 3회와 E2E-WC 현장 1회는 폐기하지 않는다. M1-VIS-2 replay와 SM-S911N shadow 뒤 전북대 A·B 사람 사전 점검이 가능할 때 각각 별도 성공 조건으로 실행한다.

## 작업 경계

현재 M1 작업에서는 다음 M2 소유 경로를 수정하지 않는다.

- `android/feature/ai-perception/**`
- `data/ai-evaluation/**`
- AI recording importer, tracker, segmentation evaluator 및 관련 보고서
- `docs/ai_offline_evaluation_plan.md`

## 근거 문서와 결과

- M1 구현·실험 기록: `docs/m1_ar_spike.md`
- M1 성능 기준선: `docs/m1_ar_performance_baseline_20260918.md`
- M1 전북대 내부 경로 선정: `docs/m1_jbnu_route_selection.md`
- M1 전북대 로컬 시험 절차: `docs/m1_local_field_route_jbnu.md`
- E2E-WC 전북대 재탐색 경로: `docs/e2e_wc_jbnu_route.md`
- M1-VIS 병렬 정합 스파이크: `docs/ar_ai_parallel_alignment_spike.md`
- 20분 soak 요약: `android/app/build/reports/ar-soak/20260917_235940/summary.json`
- 20분 원시 AR 로그: `android/app/build/reports/ar-soak/20260917_235940/navi-ar-logcat.txt`
- 자동 soak 스크립트: `scripts/ar_soak_test.ps1`
- AR/AI 전체 설계: `docs/ar_ai_modularization_plan.md`
- 프로젝트 체크포인트: `docs/project_checkpoint_20260918.md`
- 공간 데이터 평가: `docs/spatial_data_evaluation_report.md`
- Graph 후보와 경로 영향: `docs/graph_enrichment_candidate_report.md`
- Android 후보 화면 검증: `docs/android_field_test.md`

## 남은 문제

- non-debuggable benchmark도 CPU 평균 약 70.8%로 높고, ARCore motion-stereo/Depth 관련 스레드가 주 부하라는 정황만 확인했다. Depth on/off 인과 비교는 아직 하지 않았다.
- ARCore 네이티브 로그의 반복적인 depth rectifier 및 camera/IMU desync 경고가 사용자 가시 오류나 성능 저하로 이어지는지는 미확정이다.
- 실제 보도 정합과 접근성 정확도는 생활권 로컬 시험 및 사람 검수 전까지 미검증이다.
- 전북대 내부 25m 경로는 데스크톱 형상 선별과 로컬 산출물 생성까지 완료됐다. 현장 3회 측정 전에는 안전·접근성 또는 AR 리본 정합 성공으로 간주하지 않는다.
- E2E-WC 합성 폐루프는 통과했지만 A·B의 실제 휠체어 통행 가능성은 현장 사람 점검 전까지 `unknown`, `verified=false`다.
- 합성 위치와 에뮬레이터는 현재 선분·session-local 재탐색·도착 gate의 소프트웨어 흐름만 검증한다. 실제 GPS/AR 정합이나 현장 안전을 입증하지 않는다.
- frontend 결과·재탐색·도착 화면 일부의 안양/정적 데모 문구와 수치는 backend 세션 데이터와 아직 일치하지 않는다. 진행 중인 화면 변경과 충돌하지 않도록 이 작업에서는 수정하지 않았다.
- AI 병렬 경로의 공개 benchmark는 참고값일 뿐이다. 동일 입력의 로컬 A/B와 SM-S911N 측정 전에는 정확도 또는 실시간 성능 개선으로 주장하지 않는다.
- 90m DEM 경사는 실제 보도 종단경사가 아니며 승인 가능한 Graph 속성이 아니다. 더 정밀한 고도원이나 현장 측정 전에는 민감도 진단으로만 사용한다.
- 정사영상 참조는 독립 기준점 RMSE가 없으므로 시각 QA 위치 찾기에만 사용하고 geometry를 자동 이동하지 않는다.
