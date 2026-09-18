# 전북대 내부 P0 자동 시연 구현·인도 기록

작성 기준: 2026-09-19 작업 트리. 현재 상태는 **`implementation_ready_input_pending`**이다. 독립 구현, 최종 debug·benchmark 빌드, 가능한 단위·계측·실행 검사를 마쳤다. C01~C03 실제 촬영과 지정 실기기가 아직 없어 실제 추론부터 공간 판단·자동 우회·지도·AR·음성 갱신까지의 전체 수용 기준은 **미완료**다. 종합 근거는 [run_summary.json](../artifacts/jeonju/run_summary.json)에 기록했다. 계약 시험과 모델 로딩으로 `completed` 또는 `replay_verified_live_pending`을 선언하지 않는다.

## 기준과 범위

- 코드 감사 기준 및 작업 시작 HEAD: `fa93b5fe35b09e967636d0b946dff3b34293b1e3`. 이후 변경은 현재 작업 트리의 `git diff`로 확인한다.
- 요청은 v2.2 「전북대 내부 보행로 PoC 범위 확정」을 지목했지만 실제 전달된 명세 본문은 모두 v2.1이다. 없는 v2.2의 내용을 확인했다고 주장하지 않는다. 사용자가 직접 고정한 8개 내부 보행 구간·130.7m/153.5m 요구를 우선하여 v2.1의 기본·우회 경로 합집합을 [scope 설정](../backend/app/config/jeonju_scope.json)에 기록했다.
- `region_id=JEONJU_JBNU_SOUTH_P0`, `dataset_revision=jeonju-p0-20260918-1ace3878`, `scope_revision=jbnu-internal-eight-v1`.
- 활성 Graph는 기존 1,056 node / 1,517 edge다. 기본·우회·현재 위치 매칭 모두 같은 8개 `footway` 허용 목록을 사용한다. 새 빌더 결과는 후보 파일로만 생성하며 활성 Graph를 덮어쓰지 않는다.
- `demo_jeonju`는 접근성 `unknown`을 허용하는 시연 프로필이다. `wheelchair`의 엄격한 미확인 속성 정책과 별개이며, Graph에 폭·경사·턱 값을 만들어 넣거나 실제 휠체어 통행을 인증하지 않는다.
- 차도 횡단 없는 동선의 현장 확인은 `pending`이다. 촬영자가 실제 동선을 확인한 경우 해당 clip manifest에만 확인 사실을 기록한다. 공식 횡단보도 API·A/B 진입부 검수·신호 판단·전주시 전역 수집·모델 학습은 이번 내부 P0의 시작 조건이 아니다. OCR은 선택 기능이며 현재 핵심 흐름에 포함하지 않았다.

## 데이터·모델 고정

| 대상 | 값·정책 | 근거 파일 |
|---|---|---|
| P0 ZIP | SHA-256 `1ace3878b27854dfbe28445e3fa5e1ff0cce14169212cffa2e0059d122cbba1f`; 실파일 43개, 체크섬 42개와 CRC 검사 | `data/processed/jeonju/p0_manifest.json` |
| 활성 Graph 콘텐츠 | UTF-8 LF 정규화 SHA-256 `5b006cb4aecaf0f289f989a530d0d73c34f795809febf67aad52921f8f2aa923` | `backend/app/config/jeonju_scope.json` |
| Windows 작업 파일 바이트 | CRLF 원문 SHA-256 `2df42c81706c174ba4492bffcce0844705d2da1fd44568c86597702a9385a9db`; 줄바꿈 정규화 후 위 콘텐츠 해시와 일치 | 재생성한 P0 manifest의 graph_sha256 / graph_file_sha256을 함께 확인 |
| 반입 원본 | `data/raw/jeonju/p0/20260918/`; ZIP member를 원문 바이트로 보존 | `scripts/import_jeonju_p0.py` |
| OSM 조인 | 동봉 OSM의 ID·버전·좌표·태그·way node 순서를 저장소 원본과 비교, way→런타임 Edge 계보 작성 | `data/processed/jeonju/osm_edge_mapping.json` |
| 새 Graph 후보 | `data/processed/jeonju/candidate_graph.geojson`; 활성화 금지, 1,389 node / 1,850 edge의 별도 파생 결과 | `candidate_graph_summary.json`, `artifacts/jeonju/candidate-build.log` |
| 객체 모델 | EfficientDet-Lite0 int8 revision `efficientdet-lite0-int8/1`, 4,602,795 bytes | `android/feature/ai-perception/src/main/assets/model_manifest.json` |
| 모델 SHA-256 | `0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb` | 다운로드 시 및 앱 로딩 시 검증 |
| 모델 입력·출력 | 1×320×320×3 uint8 RGB → boxes/classes/scores/count; CPU, IMAGE mode | 모델 manifest와 `efficientdet_lite0_labels.txt` |
| 모델 패키징 | 공통 `src/main/assets/efficientdet_lite0_int8.tflite`; debug·benchmark 모두 포함 | `scripts/fetch_ai_baseline_model.ps1` |

모델은 [Google의 버전 1 배포 파일](https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite)을 사용한다. 라이선스·입출력 설명은 manifest의 [공식 문서](https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector) 참조를 보존한다. `latest`를 매 실행마다 받아 모델을 바꾸지 않는다. Scene Semantics는 지원되는 단말의 ARCore 기능이며 이 객체 탐지 모델에 내장된 분할 결과가 아니다.

`jeonju_p0_walk_network.geojson`의 619개 way 후보는 런타임 node/edge Graph로 대입하지 않았다. 원본 source manifest는 실제 40행이지만 동봉 설명 일부는 39행으로 기재되어 있어 차이를 반입 경고로 남긴다. 현장 실측 템플릿의 데이터 행 0개, 로드뷰 metadata 6개를 실측 또는 영상 AI 검증 성공으로 바꾸지 않는다.

과거 `metadata.demo`에 남은 **163.3m 우회는 계단 간선 2개가 포함된 제약 없는 감사 결과**다. 현재 자동 시연 기준은 실제 `RouteEngine`과 `demo_jeonju`로 계산한 초기 출발점의 **130.7m → 153.5m**, 두 경로의 계단 0개다. 검증 스크립트의 지정 차단은 `contract_test`이며 실행 중 실제 관측을 대체하지 않는다. 이동 후 우회 거리는 현재 진행점에서 계산하며 이 숫자에 맞추지 않는다.

## 구현 연결과 핵심 파일

| 영역 | 구현 | 핵심 파일 |
|---|---|---|
| ZIP 반입·검증 | 경로 이탈·중복·symlink·과대 압축 해제 방지, CRC/해시, CRS/좌표 순서·수량·OSM 계보·scope 확인 | `scripts/import_jeonju_p0.py`, `scripts/validate_jeonju_p0.py` |
| Graph 생성 | 필요한 OSM 태그를 먼저 보존하고 통행 금지 처리 후 속성 변화·장벽 노드를 유지해 단순화. 폭·경사 단위와 원문을 보존, 후보만 출력 | `scripts/build_jeonju_graph.py`, `scripts/build_graph.py` |
| 범위·현재 위치 | 허용 목록 공통 적용, 미터 단위 투영·오차·모호성 검사, 간선 중간에 요청 전용 가상 노드 삽입 | `backend/app/poc_scope.py`, `routing.py` |
| 세션 회피 | region/dataset/scope/graph/route revision, fresh current position, event 멱등성, 원자적 저장, 회피 upsert/remove, TTL stale 유지 | `backend/app/poc_sessions.py`, `database.py`, `schemas.py`, `services.py` |
| 서버 준비 | 전주 전용 DB·콘텐츠 해시 바인딩, Graph 누락 시 실패, `/health`, 고정 차단 없는 `/demo/jeonju` | `backend/app/main.py` |
| 실제 프레임 | ARCore 단독 카메라 소유, CPU YUV plane·stride·회전, 같은 stamp의 pose/intrinsics/실측 Depth/confidence/semantic mask 복사와 Image 해제 | `ArPerceptionCapture.kt`, `ArCoreNavigationView.kt`, `FramePixels.kt` |
| 추론 자원 | 동시에 추론 1개, 대기 최신 1개, drop/취소/종료에서 lease 해제 | `LatestFramePipeline.kt`, `MediaPipeObjectDetectorEngine.kt` |
| 정합 | 실제 두 기준점에 ARCore Anchor를 만들고 매 프레임 Anchor pose로 AR world↔WGS84 변환 갱신. 기준점 지도 좌표·오차·이름·revision·생성 시각은 고정 | `core/guidance-contract/.../SpatialCapture.kt`, `ArCoreNavigationView.kt` |
| 공간 판단 | Depth와 mask·timestamp·추적·정지·2초 지속·점유·오차 검사. 독립 mapper가 허용 geometry·진행 방향·유일성 확인 | `feature/guidance-fusion/.../SpatialGuidanceFusion.kt` |
| 자동 흐름 | Live/Replay 공통 `processFrame`→Fusion→Mapper→세션 API. 단일 `RouteSnapshot`으로 지도·거리·AR·TTS 상태 갱신 | `app/.../demo/JeonjuCoordinator.kt`, `JeonjuDemoScreen.kt` |
| 응답 적용 | 활성 상태·generation·요청/현재/응답 세션 및 route/Graph revision을 UI·AR·음성 변경 전에 검사. 경로 없음 응답에도 동일 적용 | `core/guidance-contract/.../RouteResponseGate.kt` |
| 진행·도착 | 유효한 현재 위치의 경로 진행점으로 남은 거리 갱신. 오차·모호성·timestamp·연속 관측을 검사한 도착 gate | `core/guidance-contract/.../PocNavigationProgress.kt`, `JeonjuCoordinator.kt` |
| 녹화·재생 | schema 2로 MP4·CPU PNG·Depth·mask·프레임별 Anchor 정합 보존. 실제 MP4 영상/길이 검사 후 manifest를 atomic finalize. 재생 때 객체 탐지는 다시 수행 | `app/.../demo/ClipArchive.kt`, `scripts/validate_jeonju_clip.py` |
| 실행·수집 | ZIP·모델·APK·기기·서버 검사, 전용 DB, 명시 ADB serial, 설치·reverse·시작·로그·화면·export 및 memory/thermal/battery 표본 수집 | `scripts/run_jeonju_demo.ps1` |
| 실행 근거 평가 | 실제 추론 이벤트→관측→요청→적용 연쇄, 고정 버전, 별도 평가 정답, TTS callback·GL 제출·성능 목표를 구분하여 평가 | `scripts/assess_jeonju_run.py` |

임시 회피는 이 세션에만 적용되며 Graph의 `blocked`, `verified`, `human_reviewed`, `field_verified`를 변경하지 않는다. TTL이 지나면 `stale_unconfirmed`로 남겨 관측 만료만으로 복귀하지 않는다. clear 요청에는 해제 근거가 필요하다. 장애물이 있는 간선 내부에서 재탐색할 경우 관측 위치·오차로 장애물과 분리됨이 확인된 반쪽 구간만 탈출에 사용할 수 있다. 근거가 없으면 그 구간 전체를 제외한다.

`RouteSnapshot`의 revision 일치는 갱신 명령의 일관성을 뜻한다. `guidance_snapshot_dispatched`는 화면 실제 렌더 완료나 TTS 실제 발화 완료를 증명하지 않으며 로그에도 `render_or_audio_completion_verified=false`를 남긴다. `guidance_render_submitted`는 해당 revision의 실제 GL draw 명령 제출을 기록한다. draw 제출·GL 작업 시간은 화면에 올바른 픽셀이 표시됐다는 확인이나 실제 보도 정합 증거가 아니다. 앱은 전체 render sample의 frame interval/작업 시간 p95를 요약하며 JSONL에는 revision의 첫 제출과 주기 표본을 남긴다. TTS는 `tts_started`/`tts_finished` callback과 session:revision을 연결하지만 실제 현장 가청성과 문장 적합성은 별도 확인한다.

현재 위치가 유효하면 같은 snapshot의 진행점을 따라 `remaining_m`을 갱신한다. 정확도 1.5m 이하, 마지막 유효 선분, 오차를 더한 목적지 거리·남은 거리 각각 2m 이내에서 서로 다른 관측 3개 이상이 2초 이상 이어져야 도착으로 처리한다. 500ms를 넘는 입력 공백·역순 프레임·위치 모호·정합 저하는 누적을 끊는다. route/calibration/tracking epoch가 바뀌면 이전 누적을 승계하지 않는다. 도착 시 안내를 정지하고 도착 음성·기록 저장으로 넘어가며, 사용자의 종료 버튼을 도착 성공으로 바꾸지 않는다. 이 수치는 구현의 gate이며 현장 정확도 달성값이 아니다.

추가 코드 검토에서는 다음 경계를 보강했다. 이 변경의 최종 APK·실행 증거는 아래 검증표에서 별도로 확인한다.

| 발견한 실패 조건 | 변경 이유와 동작 |
|---|---|
| 원본 간선이 현재 경로의 잘린 출발점 뒤까지 이어짐 | 객체를 원본 Edge의 끝점에 강제로 투영해 현재 경로 위에 있는 것처럼 판단하지 않는다. 현재 snapshot의 실제 부분 geometry를 기준으로 출발점 뒤·목적점 이후 관측을 제외한다. |
| 추론 후 최신 위치가 모호하거나 범위를 벗어나 서버가 거부 | 위치·관측 부족에 해당하는 거부는 `reroute_deferred`로 남긴다. 기존 2D 경로를 유지하고 AR·남은 거리·도착 판단을 보류하며, 재시도 간격 뒤 새 프레임의 유효한 위치·관측으로 다시 판단한다. 전체 앱 종료나 과거 위치 재전송으로 처리하지 않는다. |
| 자체 작업의 제한시간 만료가 일반 coroutine 취소와 섞임 | 준비·경로·재탐색 작업의 자체 timeout은 명시적인 실패로 표시한다. 종료/lifecycle에 따른 상위 작업 취소는 취소로 전달하여 준비 중 상태가 조용히 남거나 성공으로 바뀌지 않게 한다. |
| MapLibre의 이전 style/카메라 맞춤/끝점 표식 callback이 늦게 실행됨 | 요청별 guard와 `DisposableEffect` 수명 검사로 현재 경로에 해당하는 callback만 적용한다. 새 route가 적용되거나 지도 화면이 사라진 뒤 이전 geometry·badge가 다시 표시되는 경로를 차단한다. |

runner는 이미 존재하는 Python 환경도 필요한 의존성·버전을 검사하고 누락 또는 비호환이면 설치 후 다시 검사한다. 빌드 출력 APK를 run 전용 디렉터리에 먼저 복사하고 그 사본의 assets·해시를 검사해 설치한다. 다른 빌드가 공용 출력 파일을 바꿔도 해당 실행의 설치 바이트와 기록한 해시가 분리되지 않게 한다.

## 요구사항별 증거와 미검증 항목

아래의 「구현/계약 확인」은 현장 합격이 아니다. 실행 증거가 없거나 최종 코드와 다른 APK의 증거만 있는 행은 미검증을 그대로 유지한다.

| 요구사항 | 현재 근거 | 판정·남은 검증 |
|---|---|---|
| 작업 트리·첨부·기준 커밋 확인, 기존 구조 재사용 | 시작 HEAD와 현재 diff, 기존 Kotlin/Compose/MapLibre/ARCore/FastAPI/NetworkX/SQLite 모듈 | 구현 확인; v2.2 원문은 미제공 |
| 8구간 공통 범위, 경계 밖 강제 매칭 금지 | scope 설정, `test_strict_profile_unknown_and_scope_enforcement`, `test_outside_and_stale_position_never_snap` | 계약 확인 |
| 실제 엔진 130.7m/153.5m, 계단 0, 경로 없음 | `artifacts/jeonju/validation.json`, `test_no_route_and_expiry_does_not_reopen` | 계약 확인; 현재 Graph 콘텐츠 해시로 최종 산출물 재생성 완료 |
| ZIP 42/42·CRS·OSM ID·revision | P0 manifest, importer, ZIP 경로 이탈 테스트 | 검사 확인; ZIP 무결성은 통행 사실 검증이 아님 |
| source 보존·demo 프로필·strict wheelchair | profiles/schemas/constraints, strict unknown 테스트 | 계약 확인; 폭·경사·턱은 미확인 |
| 전주 DB·Graph 누락 실패·타 지역 fallback 0 | 서버 설정과 전용 runner DB, scope/hash 검사 | 코드 확인·기존 SelfTest 서버 왕복 확인 |
| 공식 모델 고정·두 APK 실제 로딩 | 모델 manifest, 최종 두 variant의 SelfTest 아래 참조 | 최종 APK 해시와 실제 설치·로딩 확인 |
| ARCore 단독 CPU 프레임·실측 Depth/mask·정합 | `ArPerceptionCapture`, `SpatialCapture`, fusion의 bool-only 거부 | 구현 확인; 지정 실기기 센서 동시 사용 미검증 |
| stride·회전·좌표 변환·단조 시각 | FramePixels와 capture/clip 계약 | Android 계측 7개 통과; 실제 센서 동시 입력은 미검증 |
| 처리 1+최신 대기 1, drop/취소 자원 해제 | `LatestFramePipelineTest` | 단위 계약 확인; 실제 10분 누수 시험 미실행 |
| 실제 두 점 위치·방향·스케일·정합 오차 | 두 ARCore Anchor와 프레임별 변환, 기준점 불변/Anchor 재배치 계약 검사 | 합성 geometry 계약 확인; 실제 기준점/25m 오차 미확보 |
| MP4·frames·calibration·Depth·mask 자동 export | schema 2 ClipWriter/Reader, finalized/미디어 길이 확인, host validator | 코드·형식 계약 확인; 실제 현장 export 미생성 |
| NoOp 교체·움직임·점유·지속성·유일 매핑 | `SpatialGuidanceFusionTest`, 실제 공통 pipeline 연결 | 합성 공간 계약 확인; C01~03 실제 장면 미검증 |
| 고정 차단 제거·실제 관측 EdgeImpact | bootstrap에서 block ID 제거, mapper·event 연결 | 코드 확인; 실제 frame→Edge 연쇄 미검증 |
| 현재 위치·시각·오차·간선 중간 출발 | current-mid-edge, impacted-edge retreat 테스트 | 서버 계약 확인; 실제 이동 후 재탐색 미검증 |
| 세션 회피 upsert/remove·TTL·중복·revision·격리 | P0 세션 테스트, 재시작 후 멱등성·회피 유지 검사 | 서버 계약 확인 |
| 앱의 늦은 응답 폐기·같은 snapshot | `RouteResponseGate`와 stopped/restarted/session/Graph/중복 revision 단위 검사, Coordinator 적용 | 순수 gate 계약 확인; 지연 응답의 실제 화면·음성 시험 대기 |
| 이동 중 남은 거리·자동 도착 | `PocNavigationProgressTracker`와 Coordinator, 오차/공백/모호성/도착 계약 검사 | 구현·합성 위치 계약 확인; 실제 이동 장면 미검증 |
| 정합 실패 시 리본 숨김·2D/TTS, 경로 없음 시 안내 중지 | coordinator·renderer 분기 | 구현 확인; 실제 tracking 손실/복구 수용 시험 대기 |
| Live/Replay 같은 추론·판단·매핑·API | 공통 `processFrame` 호출, 입력 공급원·시계 분리 | 코드 확인; 같은 실제 clip/현장 비교 미실행 |
| one-script 준비·설치·연결·시작·수집 | 최종 두 SelfTest 및 Prepare, Replay 누락/Live 에뮬레이터 거부 결과 | 가능한 실행·입력 오류 경로 확인; 실제 촬영 대기 |
| 준비 후 시작 1회·장애물별 조작 0회 | Live 시작 버튼과 자동 요청 흐름, started event 필드 | 코드 연결 확인; 실제 시연 영상으로 확인 필요 |
| C01 실제 장면 3회 재생 | 실제 clip·평가 정답 없음 | **미실행** |
| C02 공간 밖 객체·C03 짧은 사람 통과 억제 | 합성 fusion 단위 테스트만 있음 | **실제 장면 미실행** |
| frame→observation→event→edge→route 로그 | `frame`, `observation`, `reroute_requested`, `route_applied`, 독립 run assessor | 스키마·평가 계약 구현; 실제 C01 전체 연쇄 미생성 |
| 실제 GL 제출·TTS 시작/종료와 화면 완료 구분 | `guidance_render_submitted`, `render_sample`, `tts_started`/`tts_finished` | 계측 연결; 화면 완료·현장 가청성·정합 수용 미검증 |
| 지정 실기기 같은 코드 현장 3회 | 현재 확보된 것은 에뮬레이터 SelfTest | **미실행** |
| 25m 정합 p95·보행 영역 이탈 | 과거 M1 시험 계획만 있음 | **미실행**, 130.7m/153.5m 우회 시험과 구분 |
| 10분 통합 crash/ANR/누수·성능 목표 | render/inference/network 계측, runner memory/thermal/battery 주기 수집 | **미실행**, 계측 코드와 과거 AR-only 수치는 통합 시험 증거가 아님 |
| 원본 Graph·다른 세션 불변·검수 승격 없음 | hash 검사, 격리 테스트, candidate/avoidance 분리 | 계약 확인; 실제 시연 전후에도 다시 검사 |
| APK·manifest·runner·summary·events·영상 인도 | 아래 최종 APK·run 경로와 종합 summary | SelfTest 녹화만 생성, 실제 AI 시연 녹화는 미생성 |

## 실행한 검사와 최종 갱신란

아래 결과는 최종 APK SHA와 일치하는 실행이다. 과거 실행 결과를 변경된 APK의 성공으로 승계하지 않았다.

| 검사 | 관측 결과 | 근거·한계 |
|---|---|---|
| backend 전체 | 150개 중 **148 passed / 2 skipped / 0 failures / 0 errors** | `artifacts/jeonju/backend-all.xml`; legacy 안양 외부 후보 bundle 의존 2개 skip |
| debug/benchmark 빌드·Android 단위 검사 | **두 APK 빌드 성공, 단위 95개 통과**(app 26 / core 29 / fusion 11 / AI 16 / AR 13) | `artifacts/jeonju/android-build.log`, 각 모듈 `build/test-results/` |
| Android 계측 | **7개 통과**, `ANDROID_SERIAL=emulator-5554` | `artifacts/jeonju/android-instrumentation.log`; YUV stride/rotation·sidecar·손상/미완료 입력 거부 합성 계약 검사 |
| Android lint | **오류 0 / 경고 25** | `android/app/build/reports/lint-results-debug.html`; 경고를 0으로 보고하지 않음 |
| Debug 모델 로딩 | `model_loading_verified=true`, TTS 준비 true, frames 0, reroutes 0 | `artifacts/jeonju/runs/20260919-004727-063-selftest/run_summary.json` |
| Benchmark 모델 로딩 | `model_loading_verified=true`, **TTS 준비 false**, frames 0, reroutes 0 | `artifacts/jeonju/runs/20260919-004804-777-selftest/run_summary.json`; 모델 로딩 성공과 음성 준비 미달을 구분 |
| 위 두 SelfTest의 범위 | `emulator-5554`, 합성 검정 프레임으로 모델 생성·워밍업, 실제 서버 기본 경로·지도 확인 | `field_acceptance_verified=false`; 실제 카메라·객체·Depth·정합 성공이 아님 |
| 실제 C01~C03 Replay / Live / 25m / 10분 | 실행하지 않음 | 현장 입력·지정 실기기 미확보 |

최종 APK SHA-256은 Debug `11193d76c168356c0551c10228bedd9bc875b667e33f15f9da4321706a2bc68b`, Benchmark `b4257a559a55c3fd841c693b5b5ac474888f6a4ab794af1c632f6e7b272682e9`이다. 설치한 바이트는 [Debug APK 사본](../artifacts/jeonju/runs/20260919-004727-063-selftest/app-debug.apk)과 [Benchmark APK 사본](../artifacts/jeonju/runs/20260919-004804-777-selftest/app-benchmark.apk)에 보존했다. 두 run의 `app/events.jsonl`, `screen.png`, `screenrecord.mp4`와 시작·종료의 앱 PID CPU/메모리·발열·배터리 표본을 수집했다. 각 `debug-assessment.json` / `benchmark-assessment.json`은 실제 입력 프레임 0개인 SelfTest를 `input_pending`으로 판정한다. 활성 Graph의 정규화 콘텐츠는 시작 HEAD와 같음을 다시 확인했다.

추가 계측 실행은 시작 단계에서 두 차례 `0 tests / Process crashed`로 종료했다. 원인은 확정하지 않았으며 `artifacts/jeonju/instrumentation-followup-first-failed.xml`과 `android-build-followup-first-failed.log`에 실패를 보존했다. 앱·test APK 수동 설치와 force-stop 후 직접 instrumentation으로 `7 tests`를 통과했고(`instrumentation-manual-followup.log`), 이어 Gradle 계측도 `7 tests / BUILD SUCCESSFUL`로 통과했다(`android-instrumentation.log`). 이 계측 복구 과정에는 소스 변경이 없었다. 시작 실패를 테스트 통과나 현장 안정성 증거로 세지 않는다.

괄호가 포함된 요청 ZIP 경로로 Prepare를 실행해 통과했다(`20260919-002047-835-prepare`). C01이 없는 Replay는 정확한 누락 파일과 exit 1을 반환했다(`20260919-002059-047-replay`). emulator Live는 `physical_device_required_for_live_capture`, exit 1로 종료했다(`20260919-002108-845-live`). 이 둘은 의도한 입력 보호 동작이며 Replay/Live 성공이 아니다.

이전 Debug 실행 `20260919-001653-914-selftest`에서는 에뮬레이터 TTS 서비스의 `setCallback()` 연결 오류로 준비 상태가 false였다. 최신 Debug는 true이지만 위 Benchmark 실행은 false이므로 음성 준비 완료로 주장하지 않는다. 실패 기록을 보존하고 새 실행에서 확인해야 하며, 에뮬레이터 음성 준비를 실기기 음성 수용 성공으로 해석하지 않는다.

## 실행 명령

저장소 루트 `C:\Project\ARNAVIGATION-JEONJU`에서 PowerShell로 실행한다. Android Studio의 JBR, Android SDK 36/platform-tools, Python 3.11 이상과 설치·USB 디버깅이 가능한 기기가 필요하다. runner가 전용 환경·모델·APK·서버와 `adb reverse`를 준비하며, 실행 중 PC의 백엔드 연결이 필요하다. `-DeviceSerial`을 명시하면 다른 연결 기기를 선택하지 않는다.

```powershell
# 현장 입력 없이 데이터·모델·APK·서버 사전 검사
.\scripts\run_jeonju_demo.ps1 -Mode Prepare -Variant Benchmark

# 모델 설치·로딩과 서버 기본 경로 확인만 수행
.\scripts\run_jeonju_demo.ps1 -Mode SelfTest -Variant Debug -DeviceSerial emulator-5554 -DurationSeconds 90
.\scripts\run_jeonju_demo.ps1 -Mode SelfTest -Variant Benchmark -DeviceSerial emulator-5554 -DurationSeconds 90

# 실제 단말 serial로 바꾼 뒤 C01 촬영 준비 화면 진입
.\scripts\run_jeonju_demo.ps1 -Mode Live -DeviceSerial '<adb-device-serial>' -ClipId C01 -Variant Benchmark -DurationSeconds 900

# 실제 촬영 export가 data/replay/jeonju_p0/C01에 생긴 후 실행
.\scripts\run_jeonju_demo.ps1 -Mode Replay -BundleZip '.\data\incoming\NaVi_Jeonju_P0_FINAL_20260918(1).zip' -ClipId C01 -DeviceSerial emulator-5554 -Variant Benchmark
```

ZIP 기본 경로는 `data/NaVi_Jeonju_P0_FINAL_20260918.zip`이다. 파일명이 다른 경우 `-BundleZip`에 실제 경로를 지정한다. 괄호·공백은 따옴표로 감싼다. `Replay`는 `data/replay/jeonju_p0/<ClipId>`의 실제 공간 기록을 요구하며 없는 clip을 가짜 입력으로 대체하지 않는다. 에뮬레이터 Replay가 통과하더라도 Live 수용 기준과 성능을 입증하지 않는다.

runner의 `DurationSeconds`는 준비와 촬영을 포함한 앱 결과 대기 제한이며 5~900초다. 10분 통합 시험에서는 900초로 시작하고 초기 준비 뒤 600초 이상 실행한다. 현재 화면 녹화 수집은 Android `screenrecord`의 최대 180초 구간이므로 전체 10분의 화면 증거가 아니다. 전체 촬영 MP4·이벤트와 별도 성능 계측을 함께 확인해야 한다.

## 현장에서 필요한 최소 준비·촬영

1. ARCore **Depth와 Scene Semantics를 함께 지원하는 실제 단말**을 USB 연결하고 디버깅/카메라/위치 권한 및 한국어 TTS를 준비한다. 기존 시험 기기 SM-S911N을 우선 사용할 수 있지만 현재 연결·동시 센서 지원·이번 빌드 성능은 새로 확인해야 한다.
2. 허용 회랑에서 실제 위치가 알려진 두 기준점을 5m 이상 떨어지게 준비한다. 앱에서 각 지점의 이름·WGS84 위도·경도·근거가 있는 위치 오차(0.01~0.5m)를 입력하고 같은 AR 세션에서 카메라를 기준점에 놓아 A, B를 차례로 기록한다. GPS 한 점이나 임의로 적은 오차를 정밀 정합 근거로 사용하지 않는다. 원래 map 거리와 AR 거리 차이가 허용 오차를 넘으면 정합을 거부한다.
3. 촬영 동선에 실제 차도 횡단이 없음을 현장에서 확인하고 준비 체크를 한다. 이는 폭·턱·경사 접근성 인증과 별개다. 객체를 두거나 통제할 때 실제 보행자를 방해하는 동선을 만들지 않는다.
4. 각 장면 30~90초를 목표로 **시연 시작 1회**를 누른 뒤 촬영한다. 장애물별 제보·승인·재탐색 버튼은 없다. 종료 버튼은 기록 내보내기를 위한 촬영 종료다. 추적 손실로 정합이 무효화되면 새 준비를 수행한다.
5. C01은 분기 진입 전부터 진행 공간에 정지한 자전거 등 지원 객체와 우회 구간을 촬영한다. C02는 보도 진행 공간 밖의 자전거·차량, C03은 잠깐 지나간 뒤 사라지는 사람을 촬영한다. `-ClipId C02`, `-ClipId C03`로 각각 실행한다.
6. 「종료하고 기록 내보내기」 후 runner가 파일을 PC로 수집한다. 기존 같은 ClipId는 자동 덮어쓰지 않는다. 재촬영 원본은 run별 export에 보존하고 검토한 clip을 Replay 입력으로 채택한다.

기준점 오차는 현재 보수적 정책으로 전파한다(`reference_error + distance*sin(yaw_error) + 0.005*distance + 0.001*seconds`). 이 수식으로 계산한 값은 실제 AR tracking 정확도의 측정치가 아니다. 유효 시간·거리·오차가 부족하면 공간 매핑을 보류하며, 목표 성공률을 위해 오차를 낮춰 쓰지 않는다.

## clip 형식과 산출물 경로

앱의 원본 export 위치:

```text
/sdcard/Android/data/kr.co.navi.mobility/files/jeonju/exports/<run_id>/<clip_id>/
  recording.mp4
  clip_manifest.json
  frames.jsonl
  calibration.json
  images/<frame_id>.png
  depth/<frame_id>.u16
  depth/<frame_id>.conf
  semantics/<frame_id>.u8
  semantics/<frame_id>.conf
```

`frames.jsonl`은 실제 처리한 CPU frame의 `frame_id`, `timestamp_ns`, 원래 UTC, PNG 경로, 회전, tracking/tracking epoch, AR pose `[x,y,z,qx,qy,qz,qw]`, intrinsics `[width,height,fx,fy,cx,cy]`, CPU→texture/viewport 변환, **그 프레임의 Anchor pose로 계산한 calibration 전체**, 지도 위치·오차, 측정 바닥 높이와 depth/mask 참조를 저장한다. Depth는 little-endian uint16 mm, 0은 invalid이며 confidence는 별도 u8 파일이다. mask는 ARCore SemanticLabel과 confidence를 보존한다. 입력 누락·stale 프레임을 유효한 측정으로 채우지 않는다.

`clip_manifest.json`은 `schema_version=2`, `frame_calibration_policy=per_frame_ARCore_anchor_poses`, `completion_state=finalized`, 실제 미디어에서 확인한 `recording_duration_ms`, 지역·dataset/scope/Graph·모델 해시·기기·SDK·CPU 영상 크기/회전·수집 시각·파일별 SHA-256을 담는다. Writer는 영상 track과 실제 길이를 확인한 후 pending 파일을 atomic rename하여 확정한다. 중단·실패한 폴더, pending manifest, schema 1 기록은 완성된 Replay 입력으로 인정하지 않는다.

`calibration.json`은 최초 두 실제 기준점과 정합 identity/생성 시각을 보존한다. 각 frame은 같은 기준점의 지도 좌표·위치 오차·이름·revision·생성 시각을 유지하면서 Anchor의 AR world 좌표와 계산 변환을 갱신한다. 동일 calibration으로 tracking epoch를 건너뛰거나 임의 기준점을 바꾸면 거부한다. 시작점의 AR world 좌표를 녹화 전체에 고정하지 않는다. host validator는 schema·해시·공간 수치·파일 크기 등을 검사하고, 실제 미디어 decode는 Android Reader가 검사한다. 둘 다 데이터가 실제 현장에서 촬영됐다는 독립 인증이나 AI 성공 판정은 아니다.

객체 탐지는 Replay에서 정확히 대응하는 PNG로 새로 수행한다. Scene Semantics는 **촬영 시 센서 mask 재생**이며 해당 Replay에서 새 segmentation을 했다고 보고하지 않는다. MP4/센서 시각을 사람이 수동으로 맞추지 않는다.

평가 정답은 `evaluation/<clip_id>.json`에 예상 객체·영향 구간·시간 구간·음성 장면을 별도로 기록하고 최초 검토 후 고정한다. 이 파일은 추론·매핑 입력으로 읽지 않는다. 현재 실제 정답과 clip은 없다.

| 산출물 | 실제/생성 경로 |
|---|---|
| Debug APK | `android/app/build/outputs/apk/debug/app-debug.apk` |
| Benchmark APK | `android/app/build/outputs/apk/benchmark/app-benchmark.apk` |
| 실행에 고정한 APK 사본 | `artifacts/jeonju/runs/<run_id>/app-debug.apk` 또는 `app-benchmark.apk`; 해당 사본을 검사·설치하고 run summary에 해시 기록 |
| 실행 스크립트 | `scripts/run_jeonju_demo.ps1` |
| P0 입력 manifest·OSM 조인 | `data/processed/jeonju/p0_manifest.json`, `osm_edge_mapping.json` |
| run 단위 요약·검증 | `artifacts/jeonju/runs/<run_id>/run_summary.json`, `validation.json` |
| 앱 이벤트·앱 요약 | `artifacts/jeonju/runs/<run_id>/app/events.jsonl`, `app/run_summary.json` |
| 서버·설치·ADB 로그 | 같은 run의 `server.stdout.log`, `server.stderr.log`, `native-*.log`, `logcat.txt` |
| 실행 화면·화면 녹화 | 같은 run의 `screen.png`, `screenrecord.mp4`(SelfTest는 모델/지도 준비 녹화) |
| 기기 memory/thermal/battery | 같은 run의 `start-*.txt`, `<HHmmss>-*.txt`, `end-*.txt`; 종류는 `meminfo`, `thermalservice`, `battery` |
| 독립 실행 근거 평가 | `scripts/assess_jeonju_run.py`; `--output`으로 지정한 `assessment.json` |
| 실제 촬영 export PC 사본 | `artifacts/jeonju/runs/<run_id>/exports/<clip_id>/`(현장 촬영 후 생성) |
| Replay 입력 | `data/replay/jeonju_p0/<clip_id>/`(처음 수집한 export만 자동 복사) |
| 서버 DB | `data/runtime/jeonju_p0_<dataset_revision>_<run_id>.db`; 기존 서버 재사용 시 health revision 일치 필요 |

runner는 앱 시작·종료와 실행 대기 중 약 30초마다 `dumpsys meminfo/thermalservice/battery`와 앱 PID만 지정한 `top` CPU 표본을 수집한다. `top`의 두 번째 표본은 1초 간격 관측이며 멀티코어 사용률은 100%를 넘을 수 있다. `<label>-metrics.json`에 PID·관측 시작/종료 시각과 누락 사유를 함께 남긴다. 명령별 지연으로 수집 간격은 달라질 수 있다. 이 표본들은 연속 CPU 평균이나 crash/ANR 부재·프레임 누수 0건을 단독으로 입증하지 않는다. `open_frame_leases`, 실제 실행 시간, 로그·process 상태와 함께 검토해야 한다.

## 독립 실행 근거 평가

`assess_jeonju_run.py`는 수집된 host/app summary와 events를 읽고 버전·모델 해시, frame→observation→event→edge→route 연쇄, 세션/revision, 자동 시작 횟수, TTS callback, GL 제출 및 성능 목표를 평가한다. 실제 시연에 합성/contract 이벤트가 섞이거나 다른 run 증거를 섞으면 거부한다. 추론·재탐색을 실행하거나 평가 파일에서 Edge를 앱에 주입하지 않는다.

```powershell
# 정답 없이 실행 로그의 구조·연쇄·누락만 검사
.\.venv\Scripts\python.exe scripts\assess_jeonju_run.py 'artifacts\jeonju\runs\<run_id>' --output 'artifacts\jeonju\runs\<run_id>\assessment.json'

# 실제 촬영을 한 번 검토하여 고정한 평가 정답을 별도로 적용
.\.venv\Scripts\python.exe scripts\assess_jeonju_run.py 'artifacts\jeonju\runs\<run_id>' --evaluation 'evaluation\C01.json' --output 'artifacts\jeonju\runs\<run_id>\assessment.json'
```

| 평가 JSON 필드 | 계약 |
|---|---|
| `clip_id` | 실행 summary의 clip과 같은 ID |
| `expected_reroutes` | 검토한 장면에서 기대하는 재탐색 횟수, 0 이상의 정수 |
| `expected_edge_ids` | 실제 장면에서 검토한 영향 구간 ID 목록; 음성 장면은 빈 목록 |
| `expected_labels` | 실제 모델 검출을 확인해야 할 라벨 목록. C02/C03에서도 객체가 검출됐다는 근거가 있어야 억제를 평가할 수 있음 |
| `input_mode` | 선택 항목, `Live` 또는 `Replay` 기대 입력 |
| `time_window_ms` | 선택 항목, 첫 촬영 frame 기준 `[시작, 끝]`; 기대 조치가 이 범위 안에 있어야 함 |
| `expected_route_status` | 선택 항목, `recalculated` 또는 `no_accessible_route`; 미지정 시 경로 가용성을 기대값으로 평가하지 않음 |

평가 결과 `evidence_status`는 `rejected / input_pending / verified_partial`이며 **전체 목표의 완료 상태가 아니다**. `verified_partial`도 검사별 `pending`을 포함할 수 있다. `whole_project_acceptance=not_issued`, `field_acceptance_verified=false`를 유지한다. 명령 종료 코드 0도 전체 합격을 뜻하지 않는다. 잘못된 구조/상충 증거는 종료 코드 1이고, 입력 부족은 코드 0과 `input_pending`으로 명확히 분리한다. `render_submission`과 `render_completion`은 별도 항목이므로 GL 제출만으로 화면 완료가 통과하지 않는다. 성능 미측정은 `pending`, 목표 미달은 `not_met`이며 성공률을 맞추기 위해 품질 gate를 낮추지 않는다.

## 남은 수용 시험

실제 C01을 독립 세션으로 3회 재생하여 이벤트 연쇄와 올바른 구간·현재 진행점·지도·AR·음성을 확인한다. C02/C03에서도 탐지를 실행하되 불필요한 우회가 없는지 확인한다. 공간 모호·범위 이탈·Depth 부재·정합 손실·경로 없음·늦은 응답은 해당 실패 입력과 화면/음성 결과를 별도로 남긴다. backend 계약 시험과 합산하지 않는다.

같은 판단 코드·고정 benchmark APK로 지정 실기기 현장 3회를 수행하고 성공/실패 모두 남긴다. 25m 구간은 횡오차 p95 0.5m 목표와 실제 보행 영역 이탈을 측정한다. 10분 통합 실행은 crash·ANR·frame lease 누수 0건, 추론 3~5Hz, 추론 p95 ≤200ms, 조치 성립 후 backend p95 ≤1초, 응답 후 화면 갱신 ≤0.5초, 최초 명확한 관측→새 안내 ≤5초, 렌더링 30fps 목표와 CPU/PSS/온도/누락을 측정한다. 현재 이 숫자는 **달성값이 아닌 목표**다.

최종 상태는 실제 증거에 따라 판정한다. 독립 구현·가능한 빌드·검사가 끝난 뒤 필수 현장 입력만 없으면 `implementation_ready_input_pending`, 실제 C01~03 Replay 수용 기준을 통과하고 현장만 남으면 `replay_verified_live_pending`, Replay·실기기·정합·통합 수용 기준을 모두 충족해야 `completed`다. 코드/환경 문제가 남아 준비 단계도 충족하지 못하면 그 실패를 명시한다.
