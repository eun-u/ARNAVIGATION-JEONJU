# NaVi 프로젝트 마스터 계획과 현재 상태

마지막 갱신: 2026-09-19 KST
현재 활성 작업: 전북대 내부 8구간 자동 시연 — 구현·가능한 검사 완료, 실제 C01~03 기록과 실기기 수용 시험 대기

이 문서는 NaVi 개발의 **단일 작업 기준**이다. 날짜가 붙은 체크포인트, 초기 제안서, 개별 모듈 문서와 상태가 충돌하면 이 문서의 `현재 상태`, `결정 사항`, `바로 다음 작업`을 우선한다. 세부 설계와 원시 결과는 링크된 문서에 남기되, 작업을 마칠 때마다 이 문서에 완료 근거와 다음 시작점을 반영한다.

## 현재 상태 — 전주 자동 시연

**전체 목표는 미완료다.** 사용자 요청은 준비 후 시작 1회로 실제 카메라/녹화 프레임 추론 → 공간·지속성 판단 → 영향 구간 매핑 → 세션 임시 회피 → 현재 진행점 재탐색 → 지도·AR·음성 갱신을 수행하고 실제 Replay·실기기 수용 기준을 입증하는 것이다. 모델 로딩·합성 계약 시험·과거 AR-only 결과로 이 목표를 축소하지 않는다.

현재 상태는 **`implementation_ready_input_pending`**이다. 전주 전용 반입/검증, 고정 scope, 프로필, 세션 API, 실제 YUV/Depth/Semantics 입력, 공간 융합, Live/Replay 공통 흐름, 녹화/export 및 실행 runner를 연결했다. 실제 두 ARCore Anchor의 프레임별 정합, schema 2의 완료 clip만 허용하는 계약, `RouteResponseGate`, 현재 진행점의 남은 거리·도착 gate, GL 제출/TTS callback 계측, 독립 run 평가와 memory/thermal/battery 수집을 추가했다. 최종 두 APK 빌드·모델 로딩, Android 단위 95개·계측 7개 통과, lint 오류 0·경고 25를 확인했다. 실제 장면 통합과 현장 수용 기준은 아래와 같이 미완료다. 종합 증거는 `artifacts/jeonju/run_summary.json`에 기록했다.

| 현재 작업 | 상태 | 근거·다음 검증 |
|---|---|---|
| ZIP·OSM 계보·범위·모델 고정 | 구현·검사 확인 | 43파일/42체크섬, 8 footway, 공식 EfficientDet v1 SHA; 최종 manifest 재생성 확인 |
| 실제 엔진·현재 위치·세션 계약 | 검사 완료 | backend 148 passed / 2 skipped, 130.7m→153.5m 및 계단 0; `artifacts/jeonju/backend-all.xml` |
| 실제 인식·공간 융합·자동 실행 | 구현·독립 검사 완료 | `SpatialGuidanceFusion`, `JeonjuCoordinator`, `ClipArchive`; 실제 C01~03 미검증 |
| 녹화·Anchor·응답·진행점 계약 | 구현·계약 검사 완료 | 프레임별 정합·기준점 identity 고정, finalized MP4 길이, stale 응답 gate, 남은 거리·도착; 실제 export 대기 |
| 실행 근거 평가·계측 | 구현·가능한 검사 완료 | `assess_jeonju_run.py`, 실제 GL 제출·TTS callback, memory/thermal/battery 표본; 전체 합격 자동 발행 안 함 |
| debug·benchmark 모델 로딩 | 최종 APK 확인 | emulator-5554의 20260919-004727-063 / 004804-777 SelfTest, frames 0; TTS는 Debug true / Benchmark false로 구분 |
| C01 3회 Replay·C02/C03 억제 | 입력 대기 | 실제 ARCore 기록·정합·Depth/mask·별도 평가 정답 필요 |
| 지정 실기기 현장 3회·25m·10분 통합 | 미실행 | 실제 단말·현장 기준점·촬영 필요; 과거 M1 결과 재사용 금지 |

2026-09-19 기준점 등록 후속 수정: 입력 오류와 A/B 등록 결과를 버튼 아래에 표시하고, callback 대기 5초 제한·취소와 시작 불가 사유를 추가했다. app 단위 40개 통과, lint 오류 0/경고 30, Benchmark APK 빌드를 확인했다. 휴대폰 미연결로 설치·현장 재현은 대기 중이며 이전 APK의 실행 결과를 이번 APK에 승계하지 않는다. 변경과 고정 APK 근거는 [기준점 등록 피드백 수정](jeonju_reference_feedback_20260919.md)에 기록했다.

상세 요구사항별 증거, 현재/과거 APK 구분, 정확한 파일 형식·저장 위치·실행 명령은 [전주 자동 시연 구현·인도 기록](jeonju_demo_implementation_20260918.md)을 기준으로 한다. 아래 M1·E2E-WC·M1-VIS·공간평가 완료 기록은 **당시 코드·기기·합성 또는 AR 단독 범위의 이력**이며 이번 전체 자동 시연의 완료 증거가 아니다.

추가 코드 검토에서는 잘린 현재 경로의 출발점 뒤 객체 오매핑, 최신 위치 거부가 앱 전체 종료로 이어지던 처리, 자체 timeout과 상위 coroutine 취소의 혼동, MapLibre의 이전 style/fit/badge callback을 보강했다. 위치·관측이 부족하면 기존 2D 경로를 유지하고 AR·진행 판단을 보류한 뒤 새 유효 입력으로 재시도한다. runner는 기존 Python 환경도 의존성을 검사·복구하고, run별 APK 고정 사본을 검사·설치하여 실행 해시와 바이트의 대응을 보존한다. 이 추가 변경의 최종 검사 결과는 현재 검증표와 종합 run summary에서 확인한다.

최종 단위 검사는 app 26 / core 29 / fusion 11 / AI 16 / AR 13으로 총 95개를 통과했다. 계측은 시작 단계의 `0 tests / Process crashed` 두 차례를 실패 기록으로 보존한 뒤 수동 설치·force-stop과 직접 계측 7개, 이어 Gradle 계측 7개를 소스 변경 없이 통과했다. 시작 실패의 원인은 미확정이며 실제 통합 안정성 성공으로 확대하지 않는다. 최신 두 SelfTest의 시작·종료 CPU/메모리/발열/배터리 수집은 확인했지만 Benchmark의 TTS 준비 false는 별도 재확인 항목이다.

현재 Graph는 1,056 node / 1,517 edge를 유지한다. `demo_jeonju`만 미확인 접근성 속성을 명시적으로 허용하며 기존 strict `wheelchair`를 완화하지 않는다. 과거 163.3m 우회는 계단 간선 2개가 포함된 감사 결과다. 활성 8구간 기준은 실제 엔진의 130.7m/153.5m이며, 새 빌더의 1,389 node / 1,850 edge 결과는 후보 파일로만 보존한다. 현장 차도 횡단 확인은 `pending`이고 공식 횡단보도·진입부 자료 수집은 이번 내부 P0의 선행 조건이 아니다.

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
- M1-VIS의 mask 보정 리본 B는 shadow 실험으로 유지한다. 현재 자동 시연의 visible ribbon은 실제 정합된 경로 geometry를 사용하며 정합이 무효하면 숨기고 유효한 2D·음성으로 전환한다.
- AR 렌더링은 연속 실행하고 AI는 별도 bounded worker에서 처리 1개·최신 대기 1개로 제한한다. 현재 capture 상한은 5Hz이며 3~5Hz 처리 목표 달성 여부는 실측한다. stale·저신뢰 공간 자료는 조치에 사용하지 않는다.
- 결정론적 Rule/Cost Engine이 통과 가능성을 판정하고 RouteEngine이 경로를 계산한다.
- LLM·클라우드 영상 추론은 이번 자동 시연의 필수 실행 의존성이 아니다.
- clip manifest는 schema 2와 `frame_calibration_policy=per_frame_ARCore_anchor_poses`, `completion_state=finalized`, 실제 확인한 `recording_duration_ms`를 사용한다. 실패·중단 기록을 완성된 Replay 입력으로 쓰지 않는다.
- 프레임별 Anchor world 좌표는 바뀔 수 있지만 기준점 지도 좌표·오차·이름·정합 revision/생성 시각은 유지한다. 같은 정합으로 tracking epoch를 건너뛰지 않는다.
- 현재 위치·오차·연속 관측에서 남은 거리와 도착을 계산한다. 종료 버튼이나 Replay 경과 시간만으로 도착 성공을 만들지 않는다.
- `guidance_render_submitted`는 GL 명령 제출이며 `render_verified`나 현장 정합 성공이 아니다. TTS callback과 화면 완료, 데이터 출처의 독립 확인도 각각 구분한다.
- 자동 관측은 `pending`, `verified=false`, session-local 또는 shadow mode를 유지한다.
- 안양 현장 방문은 현재 계획에 없다. 안양 자료는 공모전 데모·공간자료 분석·synthetic 시나리오에만 사용한다.
- 위치 기반 AR 검증은 사용자 생활권의 작은 임시 OSM 보행망에서 수행한다. 거리 영상은 A/B 시각화와 정적 보도 분할 시험에 사용하지만 실제 AR tracking·GPS 정확도의 Ground Truth가 아니다.

## 전체 단계 상태

| 단계 | 상태 | 현재 근거 | 다음 조건 |
|---|---|---|---|
| M0 계약·모듈 골격 | 완료 | `guidance-contract`, `ar-navigation`, `ai-perception`, `guidance-fusion` 분리 및 빌드·계약 테스트 | 경계 변경 시 회귀 테스트 |
| M1 AR 기술 스파이크 | 진행 중 | 기존 AR 실험과 M1-VIS-1 정적 A/B·fallback·JSON/SVG harness 완료 | M1-VIS-2 녹화 replay 후 SM-S911N shadow |
| E2E-WC 휠체어 가정 재탐색 실증 | 보류 | 단독 에뮬레이터에서 A 130.7m → session-local 차단 → B 153.5m → 저정확도 거부 → 3회·2초 자동 도착 폐루프 통과 | A·B 사람 사전 점검 뒤에만 현장 1회 실행 |
| M2 AI 입력·모델 | 구현·가능한 검사 완료 | 공식 모델 공통 assets, ARCore CPU YUV, 최신 프레임 파이프라인, Replay fresh inference, 최종 두 APK 모델 로딩 | 실제 C01~03 추론 검증 |
| 공간데이터·Graph 후보 | 자동화 완료·사람 검수 대기 | 247개 후보/196개 Edge, 요청 한정 시뮬레이션 17개, 근거 전용 230개, 정사영상 참조 247/247 | 계단 후보 검수·정사영상 RMSE 확보 전 Graph 승격 금지 |
| M3 공간 융합·Map Matching | 구현 연결·현장 미검증 | measured Depth/mask·두 기준점·정지/점유/지속 판단·독립 Edge mapper | 실제 정합·C01 양성/C02~03 음성 확인 |
| M4 세션 룰·재탐색 | 서버 계약 검사 확인 | 8구간 scope, 현재 간선 진행점, 원자적 회피·TTL·멱등/revision·격리 | 실제 AI 관측으로 전체 경로 갱신 반복 검증 |
| M5 LLM 설명·제안 | 미착수 | 역할과 금지 경계만 확정 | M4 결정론적 결과 안정화 |
| M6 자동 시연 수용 | 입력 대기 | 독립 서버/Android 검사와 최종 SelfTest, 전체 목표 미완료 | 실제 Replay 3회·지정 실기기 3회·25m·10분 통합 |

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

### 실제 단말에서 C01~03 촬영을 확보하고 수용 시험 재개

독립 구현 검사와 최종 APK 검증은 완료했다. `artifacts/jeonju/run_summary.json`의 APK SHA를 기준으로 실제 단말을 명시 연결하고 `run_jeonju_demo.ps1 -Mode Live`로 권한·두 기준점·동선을 준비한다. C01~03 짧은 촬영 export와 별도 평가 정답을 확보한 뒤 같은 runner로 실제 Replay 3회와 지정 실기기 3회, 별도 25m 및 10분 통합 수용 시험을 수행한다. 전체 목표는 그 전까지 미완료다.

M1-VIS-2 shadow A/B 비교는 보존된 후속 연구 항목이다. 이번 내부 P0의 시작 조건으로 A/B 진입부 검수·공식 횡단보도 수집·도시 전역 수집을 추가하지 않는다. 원본 Graph의 실제 접근성 인증은 여전히 별도 사람 검수 대상이다.

## 작업 경계

과거 M1 전용 채팅에서 정했던 M2 파일 비수정 경계는 아래 이력의 당시 작업 분담이다. 현재 사용자는 저장소 전체 자동 시연 연결을 지시했으므로 AI 입력·공통 계약·융합·앱·서버를 함께 수정한다. 기존 사용자 변경은 보존하고 병렬 작업은 파일 소유를 나누어 충돌을 피한다. 안양 공간평가·공용 Graph 승격·신규 모델 학습은 현재 변경 범위에 포함하지 않는다.

## 근거 문서와 결과

- 현재 전주 자동 시연: `docs/jeonju_demo_implementation_20260918.md`
- 현재 backend 검사: `artifacts/jeonju/backend-all.xml`
- 현재 runner 실행별 증거: `artifacts/jeonju/runs/<run_id>/run_summary.json`
- 실행 근거 평가: `scripts/assess_jeonju_run.py <run-directory> --evaluation evaluation/<clip_id>.json --output <assessment.json>`; 평가 정답은 실제 장면 검토 후 별도로 고정하며 앱 입력으로 사용하지 않음
- renderer/기기 계측: 앱 summary의 frame interval·GL 작업 시간 p95 및 run별 `meminfo`, `thermalservice`, `battery` 표본
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

- 실제 C01~C03 clip·Depth/mask·정합·평가 정답과 지정 실기기 연결이 없다. 실제 자동 매핑·반복 Replay·현장 3회·25m 정합·10분 통합 수용 기준은 미검증이다.
- 최종 APK·기록 형식의 계약 검사는 통과했으나 실제 ARCore MP4 export와 센서 동시 동작은 현장 검증이 남아 있다. SelfTest와 합성 계측 검사를 이 근거로 승격하지 않는다.
- 준비 후 시작 1회·장애물별 개입 0회는 현재 코드 흐름이며 실제 시연 영상과 이벤트로 입증해야 한다. snapshot 전달 로그만으로 렌더·발화 완료를 주장하지 않는다.
- `assess_jeonju_run.py`의 `verified_partial`은 검사별 미검증 항목을 포함할 수 있고 전체 완료 상태가 아니다. 실제 C01~C03·현장·25m·10분 수용 기준이 남으면 `whole_project_acceptance=not_issued`를 유지한다.
- runner의 CPU/memory/thermal/battery는 시작·종료/약 30초 표본이다. CPU는 해당 앱 PID의 1초 간격 top 표본이며 연속 계측·정확한 발열 추이·crash/ANR/누수 0건의 전체 증거를 대신하지 않는다. 화면 녹화도 최대 180초 구간이다.
- 과거 AR 단독 non-debuggable benchmark의 CPU 평균 약 70.8%는 이번 자동 시연 성능이 아니다. 당시 ARCore motion-stereo/Depth 관련 스레드가 주 부하라는 정황만 확인했고 Depth on/off 인과 비교는 하지 않았다.
- ARCore 네이티브 로그의 반복적인 depth rectifier 및 camera/IMU desync 경고가 사용자 가시 오류나 성능 저하로 이어지는지는 미확정이다.
- 실제 보도 정합과 접근성 정확도는 생활권 로컬 시험 및 사람 검수 전까지 미검증이다.
- 전북대 내부 25m 경로는 데스크톱 형상 선별과 로컬 산출물 생성까지 완료됐다. 현장 3회 측정 전에는 안전·접근성 또는 AR 리본 정합 성공으로 간주하지 않는다.
- E2E-WC 합성 폐루프는 통과했지만 A·B의 실제 휠체어 통행 가능성은 현장 사람 점검 전까지 `unknown`, `verified=false`다.
- 합성 위치와 에뮬레이터는 현재 선분·session-local 재탐색·도착 gate의 소프트웨어 흐름만 검증한다. 실제 GPS/AR 정합이나 현장 안전을 입증하지 않는다.
- frontend 결과·재탐색·도착 화면 일부의 안양/정적 데모 문구와 수치는 backend 세션 데이터와 아직 일치하지 않는다. 진행 중인 화면 변경과 충돌하지 않도록 이 작업에서는 수정하지 않았다.
- AI 병렬 경로의 공개 benchmark는 참고값일 뿐이다. 동일 입력의 로컬 A/B와 SM-S911N 측정 전에는 정확도 또는 실시간 성능 개선으로 주장하지 않는다.
- 90m DEM 경사는 실제 보도 종단경사가 아니며 승인 가능한 Graph 속성이 아니다. 더 정밀한 고도원이나 현장 측정 전에는 민감도 진단으로만 사용한다.
- 정사영상 참조는 독립 기준점 RMSE가 없으므로 시각 QA 위치 찾기에만 사용하고 geometry를 자동 이동하지 않는다.
