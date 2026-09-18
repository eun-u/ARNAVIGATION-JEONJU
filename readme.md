# NaVi 전주·전북대학교 접근성 경로 안내 PoC

NaVi는 스마트폰 카메라와 접근성 경로 엔진을 결합해 휠체어·개인 이동 사용자의 길을 안내하는 모바일 우선 PoC입니다. 일반 최단경로와 접근 가능한 경로를 구분하고, 이동 중 장애물 후보가 생기면 현재 세션의 경로를 다시 계산합니다.

이 저장소는 전주·전북대학교 실증용 전환본입니다. 제공된 전북대 주변 OSM을 `data/processed/jeonju_accessibility_graph.geojson`으로 빌드해 백엔드 기본 Graph로 연결했습니다. OSM Graph는 실제 공간 출처지만 보도 실측망이 아니며 모든 접근성 상태는 현장 검증 전입니다. 기존 안양 자료는 파이프라인 참고용 legacy 데이터일 뿐 전주 경로 사실로 사용하지 않습니다.

전국횡단보도표준데이터 50,000행과 [컬럼·출처 명세](data/reference/national_crosswalk/README.md)를 반입했습니다. 제공 파일에는 전주시 행이 없어 횡단보도 속성을 Graph에 병합하지 않았으며 `verified=false`, `graph_update_allowed=false`를 유지합니다.

## 현재 개발 상태 — 2026-09-18

- AR·AI·융합·공통 계약을 독립 Gradle 모듈로 분리했습니다. AR 모드의 카메라는 `:feature:ar-navigation`이 소유하고 AI에는 timestamp가 있는 frame lease만 전달합니다.
- M1 AR은 SM-S911N에서 ARCore pose/tracking, Depth, 3D route ribbon, 2D fallback, Recording/Playback과 20분 안정성 시험까지 완료했습니다. 실제 보도 위 리본 정합은 전북대 내부 25m 구간의 현장 3회 전까지 미검증입니다.
- 전북대 로컬 E2E-WC Graph의 합성 Android 시험에서 `A 130.7m → session-local 차단 → B 153.5m → 저정확도 거부 → 3회·2초 자동 도착` 폐루프를 통과했습니다. 원본 Graph와 Edge는 변경되지 않았고 관측 후보는 `pending`, `verified=false`입니다.
- ARCore 또는 후면 카메라를 사용할 수 없는 환경에서는 설치 화면을 자동 실행하지 않고 2D 안내로 강등합니다.
- M1-VIS-1 정적 frame A/B harness가 완료됐습니다. 기하 리본 A는 계속 실제 안내를 담당하고 sidewalk mask 기반 B는 shadow JSON·SVG만 생성합니다. 다음 작업은 기존 녹화 frame sequence를 같은 계약으로 재생하는 M1-VIS-2입니다.
- Android 최종 디자인 화면은 전북대 전주캠퍼스 기준 문구로 전환했습니다. 표시되는 합성 예시를 실제 전북대 실증 결과로 해석하면 안 됩니다.

상세 완료 근거, 남은 위험과 한 개로 고정한 다음 시작점은 [프로젝트 마스터 계획](docs/project_master_plan.md)을 기준으로 합니다.

## 검증 시나리오

| 상태 | 거리 | 설명 |
|---|---:|---|
| 일반 최단경로 | 130.7m | 제공 OSM Graph의 거리 기준 경로 |
| 접근 가능 경로 | 130.7m | 현재 검증된 추가 접근성 제약 없음 |
| 데모 Edge 세션 차단 후 | 163.3m | 원본 Graph를 바꾸지 않는 임시 재탐색 |

데모 Edge와 출발·도착 노드는 빌드 과정에서 연결성과 우회 가능성을 확인해 선정되며, `data/processed/jeonju_accessibility_graph.geojson`의 `metadata.demo`에 기록됩니다.

## 구현 범위

- OSMnx로 고정 시점 보행망 수집 및 GeoJSON Accessibility Graph 빌드
- NetworkX MultiGraph + Dijkstra 일반/접근성 경로 계산
- 계단, 차단, 엘리베이터, 경사, 폭, 턱 Hard Constraint
- Kotlin + Jetpack Compose 기반 Android 전용 시민 앱
- MapLibre 지도에서 일반·접근 가능·재탐색 경로 비교
- Android 후보 지도에서 계단 5건과 DEM 진단 12건의 요청 한정 전·후 경로 시뮬레이션
- ARCore 3D route ribbon과 CameraX 기반 Prismatic Wayfinding 2D fallback
- 시작 → 경로 계획 → 비교 → 판단 근거 → 지도/카메라 안내 → 현장 제보 → 재탐색 흐름
- 현장 제보 → 현재 세션 임시 차단 → 즉시 재탐색
- 시민 제보는 `pending`, `verified=false`로 저장하고 공용 Graph에는 미반영
- SQLite Edge 현재 상태와 변경 이력 영속화
- 브라우저 탭별 24시간 route session 및 영향 세션 재계산
- ONWAY AI 후보 5건의 `pending → human review → approved/rejected` 워크플로
- 승인된 관찰만 검증 상태로 Graph에 반영

AI 후보는 Graph를 직접 수정하지 않습니다. 점자블록 부재 후보는 승인하더라도 현재 휠체어 Hard Constraint에 임의로 연결하지 않습니다.

## 백엔드 실행

Python 3.11 이상과 PowerShell 기준입니다.

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev,geo]"
.\.venv\Scripts\python.exe -m uvicorn app.main:app --app-dir backend --host 127.0.0.1 --port 8000 --reload
```

- 현장 후보 검수: `http://127.0.0.1:8000/review`
- API 문서: `http://127.0.0.1:8000/docs`

SQLite는 기본적으로 `data/runtime/navi.db`에 생성되며 Git에 포함되지 않습니다.

## Android 앱 실행

요구 환경은 Android Studio, JDK 17 이상, Android SDK 36입니다. 에뮬레이터에서는 앱의 기본 서버 주소 `http://10.0.2.2:8000`이 위 백엔드를 가리킵니다.

```powershell
Copy-Item android\local.properties.example android\local.properties
# local.properties의 sdk.dir를 설치된 Android SDK 경로로 수정

cd android
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:installDebug
```

생성 APK는 `android/app/build/outputs/apk/debug/app-debug.apk`입니다. 실기기에서 백엔드에 연결하려면 `android/local.properties`의 `NAVI_BACKEND_URL`을 개발 PC의 LAN 주소로 바꾸고, 백엔드를 `--host 0.0.0.0`으로 실행해야 합니다. 운영용 HTTP 허용은 열어두지 않았으므로 실제 배포는 HTTPS 구성이 필요합니다.

Android Studio에서는 `android/` 폴더를 프로젝트로 열어 `app` 구성을 실행합니다.

경로 비교 화면의 `후보 지도와 경로 영향 보기`에서 공간평가 후보를 확인할 수 있습니다. Android 화면은 `영향 시험 / 보행공간 / 횡단시설 / 연석` 지도 레이어를 분리합니다. 영향 시험에는 계단 후보 5건과 90m DEM 경사 민감도 후보 12건이 포함되며, DEM 후보는 실제 보도 경사나 승인 가능한 Graph 값이 아닙니다. 근거 후보 상세에는 25cm 정사영상의 도엽·pixel QA 참조가 표시됩니다. 모든 계산과 조회는 `graph_mutated=false`, `database_mutated=false`이며 후보 승인이나 공용 Graph 변경을 수행하지 않습니다.

## 데이터 재생성

저장소에는 사용자 제공 ZIP에서 안전하게 선별한 OSM 스냅샷과 빌드 결과가 포함됩니다.

```powershell
.\.venv\Scripts\python.exe scripts\prepare_jeonju_data.py
.\.venv\Scripts\python.exe scripts\build_jeonju_graph.py
```

첫 명령은 ZIP에서 OSM과 지형지물 표준코드만 추출하고 다운로드 실행 파일은 제외합니다. 두 번째 명령은 전북대 OSM Graph를 생성합니다. OSM 데이터는 OpenStreetMap contributors의 ODbL 조건을 따릅니다.

## 전주 공간데이터 상태

- 적용됨: 전북대 주변 OSM `map.osm`, OSM `export.geojson`, 지형지물 표준코드 XLS
- 보류됨: 전국 횡단보도 CSV는 전주시 행 0건이라 Graph 미병합
- 미제공: 실제 DEM, 수치지형도, 상세 수치지형도, 정사영상
- 제외됨: ZIP의 `INNORIX-Agent.exe`는 다운로드 클라이언트이며 공간데이터가 아니므로 추출·실행하지 않음

안양용 `validate_spatial_sources.py`와 `run_spatial_evaluation.py`는 legacy 분석 코드다. 전주 NGII 원본이 확보되기 전에는 전주 데이터 평가 명령으로 사용하지 않는다.
- `docs/spatial_data_evaluation_report.md`: 실제 평가 수치와 다음 검수 gate
- `docs/graph_enrichment_candidate_report.md`: 경로 영향 후보와 제외 사유

종료 코드는 `0=검증 실패 없음(pass 또는 제한이 명시된 warning)`, `1=필수 데이터 검증 실패`, `2=CLI 또는 내부 실행 오류`입니다. 전체 평가는 원본 TIFF나 기존 Graph를 수정하지 않습니다. 모든 파생 산출물은 `derived=true`, `verified=false`, `graph_update_allowed=false`이며 Human Review 전에는 공유 Graph에 반영할 수 없습니다. 상세 기준은 [대표회랑 공간데이터 평가 계획](docs/spatial_data_evaluation_plan.md)을 참고하세요.

`data/processed/evaluation/`은 재배포 제한이 있는 NGII 자료의 파생 geometry를 포함할 수 있어 로컬 전용이며 Git에 저장하지 않습니다. 저장소에는 재현 스크립트와 집계 보고서만 포함합니다.

## 테스트

```powershell
.\.venv\Scripts\python.exe -m pytest
```

Android 단위 테스트·빌드·UI 테스트 APK 컴파일:

```powershell
cd android
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

연결된 에뮬레이터/기기에서 계측 UI 테스트를 실행하려면 `.\gradlew.bat :app:connectedDebugAndroidTest`를 사용합니다. 수동 실증 순서는 [Android 현장 검증 절차](docs/android_field_test.md)에 기록했습니다.

## 클라이언트 구조

- [Android 아키텍처와 화면 흐름](docs/android_architecture.md)
- [AR·AI 모듈화 및 개발 계획](docs/ar_ai_modularization_plan.md)
- [AR 기준선·AI 보정 병렬 정합 스파이크](docs/ar_ai_parallel_alignment_spike.md)
- [Android 현장 검증 절차](docs/android_field_test.md)
- [M1 전북대 내부 로컬 AR 정합 시험](docs/m1_local_field_route_jbnu.md)
- [M1 전북대 내부 PoC 경로 선정 기록](docs/m1_jbnu_route_selection.md)
- [E2E-WC 전북대 휠체어 가정 재탐색 경로](docs/e2e_wc_jbnu_route.md)
- [Prismatic Wayfinding 디자인 시스템](docs/frontend_design_system.md)
- [디자인 토큰 명세](docs/design_tokens.md) — Figma Variable ↔ CSS ↔ Compose
- [최종 와이어프레임 36장](docs/wireframes/final/README.md)
- [Screen State Matrix](docs/screen_state_matrix.md)
- [Routing Constraint Model](docs/routing_constraint_model.md)
- [Data Trust Model](docs/data_trust_model.md)
- [Interaction Spec — Motion · Haptic · Voice](docs/interaction_spec.md)
- [접근성 검증 계획](docs/accessibility_validation.md)
- 기존 웹 IA·유즈케이스 문서는 초기 탐색 기록으로 `docs/frontend_ia.md`, `docs/use_cases.md`, `docs/page_structure.md`에 보존

Android 앱은 Graph 메타데이터의 `synthetic`, `verified=false` 고지를 그대로 표시합니다. 현장 제보는 현재 세션 재탐색에는 즉시 사용하지만, 승인 전까지 공용 Graph를 수정하지 않습니다.

## 주요 API

- `GET /health`
- `POST /route`
- `POST /route/compare`
- `GET /route/sessions/{session_id}`
- `POST /route/sessions/{session_id}/reroute`
- `GET /edges/{edge_id}`
- `GET /edges/{edge_id}/history`
- `PATCH /edges/{edge_id}/status`
- `GET /observations/candidates`
- `POST /observations/candidates`
- `POST /observations/candidates/{candidate_id}/review`
- `GET /graph-enrichment/summary`
- `GET /graph-enrichment/candidates?route_affecting=true|false`
- `GET /graph-enrichment/candidates/{candidate_id}`
- `POST /graph-enrichment/simulate`

직접 Edge를 변경할 때 `verified=true`를 사용하려면 확인자 `actor`가 필수입니다. PoC 화면의 수동 차단 실험은 `verified=false`로 저장됩니다.

공간평가 후보 API는 `candidate_bundle.json`을 읽기 전용으로 노출합니다. `POST /graph-enrichment/simulate`는 서버에 저장되고 `simulation_allowed=true`인 `candidate_id`만 받아 해당 요청의 Graph 사본에 제안 속성을 임시 적용합니다. 공용 Graph, SQLite, route session, graph revision은 변경하지 않습니다. 현재 시뮬레이션 가능한 후보는 `stairs=true` 제안 5개와 90m DEM 경사 민감도 진단 12개입니다. DEM 후보는 `approval_eligible=false`이고, 근거만 있는 230개 후보는 시뮬레이션이나 경로 사실로 사용할 수 없습니다.

Android의 `공간데이터 후보` 화면은 시뮬레이션 가능 후보 17개를 자동 계산하고 `경로 단절 → 추가 우회거리 → 경로 구성 변경 → 변화 없음` 순으로 정렬합니다. `보행공간 / 횡단시설 / 연석` 필터는 근거 전용 객체를 별도 지도 레이어로 조회합니다. 선택 후보에서는 현재값과 제안값, 수치지형도 provenance, 정사영상 도엽·pixel 참조를 확인할 수 있습니다. 이 순위와 참조는 민감도·시각 QA 정보일 뿐, 후보의 진실성이나 승인 상태를 뜻하지 않습니다.

## 환경 변수

```text
NAVI_GRAPH_PATH=data/processed/jeonju_accessibility_graph.geojson
NAVI_DB_PATH=data/runtime/navi.db
NAVI_GRAPH_ENRICHMENT_PATH=data/processed/evaluation/graph_enrichment/candidate_bundle.json
VITE_KAKAO_MAP_KEY=<Kakao Maps JavaScript key, optional>
KAKAO_REST_API_KEY=<Kakao REST API key, optional>
```

Kakao 키는 로컬 `.env`에만 두며 저장소에 커밋하지 않습니다. 두 키는 각각 브라우저 JavaScript SDK와 서버 REST 요청용이고 Android 네이티브 지도 키가 아닙니다. 현재 Android 지도는 MapLibre/OSM을 사용합니다.

## 데이터 신뢰도와 한계

- OSM 스냅샷: 실제 공간 출처, 현장 접근성은 미검증
- 전국 횡단보도: 제공 파일에 전주시 행이 없어 현재 미병합
- 세션 차단 데모: 원본 Graph를 변경하지 않는 실험값
- 실제 턱 높이, 경사, 폭, 엘리베이터 상태를 주장하지 않음
- 기본 카메라 화면은 영상을 서버로 업로드하지 않음. ARCore dataset 기록은 사용자가 기술 스파이크 화면에서 명시적으로 시작한 로컬 MP4·telemetry에 한함
- ARCore 3D 리본은 구현됐지만 실제 보도 정합 정확도는 현장 3회 전까지 미검증이며, 추적·위치 조건이 부족하면 2D 안내로 강등
- 실제 장애물 자동 감지와 안전 재탐색은 아직 실시간 안내에 연결하지 않음. AI 결과는 shadow/pending 상태와 사람 검수를 거쳐야 함
- 현재 기본 Graph 범위 밖 위치는 Android 앱에서 경로 출발지로 사용하지 않으며, 다른 지역의 실제 경로 검증에는 해당 지역 Graph 빌드가 필요
- 전주 OSM Graph와 접근성 속성은 현장 실측 완료 데이터가 아니므로 실제 안전을 보장하지 않음

전체 구조와 데이터 계약은 [architecture.md](docs/architecture.md), [data_schema.md](docs/data_schema.md), 실험 절차는 [experiment.md](docs/experiment.md)를 참고하세요.
