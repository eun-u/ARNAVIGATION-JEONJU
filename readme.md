# NaVi 전주·전북대학교 내부 보행로 PoC

NaVi는 Android 카메라·공간 센서와 경로 안내를 연결하는 전북대 내부 보행로 시연 앱입니다. 현재 기본 진입은 사용자 지정 동선을 내장한 **`Hackathon` 모드**입니다. 출발점에서 남쪽을 향해 시작하고, 장애물 앞에서 되돌아간 뒤 동쪽 곡선 보행로를 따라 아래 합류점에서 종료합니다. 이 모드의 안내에는 서버가 필요하지 않습니다.

**[촬영·인식·AR 실험 통합 문서와 사진 폴더](docs/jeonju-experiment-archive-20260919/README.md)**에 실제 촬영 5개, 현장 사진 153장, 탐지 검토 이미지, 라인·화살표 표현 기록, 검토 영상 4개와 검사 로그를 정리했습니다. 실제 촬영, 분석용 검출 박스, 실제 앱 캡처, 합성 설명 이미지를 구분합니다.

현재 버전은 실제 콘 인식 신호 또는 수동 확인으로 지정 경로를 전환하고, 약 2m 간격의 바닥 화살표·지도·음성을 갱신합니다. 객체 오버레이에는 이름·점수·종류별 색상을 표시합니다. [최신 사용법과 구현 범위](docs/jeonju_hackathon_preset_20260919.md)를 확인하세요. **최신 S23 설치와 전체 현장 동선의 성공은 아직 확인하지 않았습니다.** 기존 영상에서 콘 143박스를 검출한 것과 실제 자동 우회 성공은 구분합니다.

지정 코스 버전의 Android 검사는 125개 통과·2개 skip, 에뮬레이터 검사는 10개 통과했습니다. 이후 오버레이 수정에서 앱 단위 43개 통과·1개 skip, Debug/Benchmark 빌드와 lint를 확인했습니다. [시점별 검증 기록](docs/jeonju-experiment-archive-20260919/records/verification/)에 APK 해시와 범위를 보관했습니다. 아래는 별도로 유지하는 기존 서버 기반 모드의 범위와 실행법입니다.

## 기존 서버 기반 모드의 범위

- 런타임 Graph는 제공 OSM의 1,056 node / 1,517 edge이며, 서버가 허용한 **전북대 내부 footway 8개** 안에서만 기본 경로·재탐색·현재 위치 매칭을 수행합니다.
- `demo_jeonju`는 **접근성 미확인 속성 허용** 시연 프로필입니다. 폭·경사·턱의 `unknown`을 보존하고 기존 엄격한 `wheelchair`를 완화하지 않습니다.
- 고정 초기 출발점에서 실제 RouteEngine으로 계산한 기본 경로는 **130.7m**, 분기 구간 회피 우회는 **153.5m**, 두 경로의 계단은 **0개**입니다. 이동 후 우회는 현재 진행점에서 계산하므로 초기 거리와 달라집니다.
- 과거 163.3m 우회에는 계단 간선 2개가 포함되어 있었습니다. 접근 가능한 우회 성공 사례로 사용하지 않습니다. 25m M1 구간은 별도의 AR 정합 시험입니다.
- 세션 회피는 원본 Graph·다른 세션·사람 검수 상태를 변경하지 않습니다. TTL 만료는 관측이 오래됐다는 뜻이며 길이 열렸다는 확인으로 취급하지 않습니다.
- 두 실제 기준점의 ARCore Anchor로 매 프레임 정합을 갱신합니다. 검증된 진행점으로 남은 거리를 계산하고, 위치 오차·연속 관측을 통과해야 도착으로 처리합니다. 정합·진행점이 모호하면 해당 판단을 보류합니다.
- `RouteResponseGate`가 활성 상태·generation·세션·route/Graph revision을 확인한 응답만 지도·AR·음성에 적용합니다. 종료 뒤 늦은 응답과 이전 경로의 중복 응답은 폐기합니다.
- 자동 회피는 잘린 현재 경로의 출발점 뒤에 있는 객체를 제외합니다. 최신 위치가 모호하거나 범위를 벗어나면 기존 2D 경로를 유지하고 AR을 숨긴 채 새 유효 관측을 기다립니다. 자체 작업 timeout은 명시적인 실패로 처리하고, MapLibre의 오래된 비동기 callback도 폐기합니다.
- 차도 횡단 없는 동선의 실제 확인은 촬영 전까지 `pending`입니다. 공식 횡단보도·신호·진입부 검수·도시 전역 수집·모델 학습은 이번 내부 P0의 선행 조건이 아닙니다. OCR은 선택 기능입니다.

전달된 명세 본문은 v2.1이며 요청에 언급된 v2.2 원문은 없습니다. 사용자가 직접 명시한 내부 8구간과 거리 기준을 우선 적용한 근거를 [scope 설정](backend/app/config/jeonju_scope.json)에 기록했습니다.

## 기존 서버 기반 모드 실행

Windows PowerShell, Python 3.11 이상, Android Studio JBR, Android SDK 36/platform-tools를 사용합니다. 저장소 루트에서 runner 하나가 데이터·모델·APK·기기·전주 서버를 검사하고 설치, `adb reverse`, 시연 화면 진입, 결과 수집을 수행합니다. 실행 중 PC 백엔드 연결이 필요합니다.

기존 Python 환경의 누락·비호환 의존성은 설치 후 재검사합니다. 실행할 APK는 run 디렉터리의 고정 사본으로 복사한 뒤 assets·해시를 검사하고 그 사본을 설치합니다.

```powershell
# 현장 입력 없이 준비 검사
.\scripts\run_jeonju_demo.ps1 -Mode Prepare -Variant Benchmark

# 에뮬레이터에서 APK 모델 로딩·서버 경로 확인만 수행
.\scripts\run_jeonju_demo.ps1 -Mode SelfTest -DeviceSerial emulator-5554 -Variant Debug -DurationSeconds 90
.\scripts\run_jeonju_demo.ps1 -Mode SelfTest -DeviceSerial emulator-5554 -Variant Benchmark -DurationSeconds 90

# 실제 단말 serial로 바꾸고 현장 촬영 준비
.\scripts\run_jeonju_demo.ps1 -Mode Live -DeviceSerial '<adb-device-serial>' -ClipId C01 -Variant Benchmark -DurationSeconds 900

# 실제 촬영 파일이 준비된 뒤 재생
.\scripts\run_jeonju_demo.ps1 -Mode Replay -BundleZip '.\data\incoming\NaVi_Jeonju_P0_FINAL_20260918(1).zip' -ClipId C01 -DeviceSerial emulator-5554 -Variant Benchmark
```

기본 ZIP은 `data/NaVi_Jeonju_P0_FINAL_20260918.zip`이며 파일명이 다르면 `-BundleZip`에 실제 경로를 지정합니다. `Replay` 입력은 `data/replay/jeonju_p0/<ClipId>/`입니다. 없는 실제 clip을 합성 영상이나 시간 예약 차단으로 대체하지 않습니다.

현장에서는 기기 권한과 한국어 음성, 실제 위치가 알려진 5m 이상 떨어진 두 기준점을 준비하고 앱에서 A/B 정합을 기록합니다. 동선 확인 후 「시연 시작」을 한 번 누르고 C01 정지 장애물, C02 진행 공간 밖 객체, C03 잠깐 지나가는 사람을 각각 30~90초 촬영합니다. 「종료하고 기록 내보내기」가 MP4·정확한 CPU 프레임·Depth·mask·정합을 함께 저장합니다. 세부 기준점 조건과 저장 형식은 [현장 재개 절차](docs/jeonju_demo_implementation_20260918.md#현장에서-필요한-최소-준비촬영)를 확인하세요.

Replay 입력은 schema 2의 프레임별 Anchor 정합과 `completion_state=finalized`, 실제 MP4 검사로 얻은 `recording_duration_ms`를 요구합니다. 녹화 실패·중단 또는 pending manifest를 완료 clip으로 재생하지 않습니다. 녹화가 성공해도 실제 C01~C03 수용 시험 합격을 자동 선언하지 않습니다.

## 산출물과 검증 범위

| 산출물 | 경로 |
|---|---|
| Debug APK | `android/app/build/outputs/apk/debug/app-debug.apk` |
| Benchmark APK | `android/app/build/outputs/apk/benchmark/app-benchmark.apk` |
| 데이터 manifest | `data/processed/jeonju/p0_manifest.json` |
| 모델 manifest | `android/feature/ai-perception/src/main/assets/model_manifest.json` |
| 실행 결과 | `artifacts/jeonju/runs/<run_id>/run_summary.json` |
| 앱 이벤트 | 같은 run의 `app/events.jsonl` |
| 실행 화면 녹화 | 같은 run의 `screenrecord.mp4` |
| 기기 표본 | 같은 run의 `start-*.txt`, `<HHmmss>-*.txt`, `end-*.txt` (`cpu`, `meminfo`, `thermalservice`, `battery`)와 `*-metrics.json` |
| 독립 근거 평가 | `scripts/assess_jeonju_run.py`의 `--output`으로 지정한 `assessment.json` |
| 실제 촬영 export | 같은 run의 `exports/<ClipId>/`(현장 촬영 후 생성) |
| backend 검사 | `artifacts/jeonju/backend-all.xml` |

최신 debug·benchmark SelfTest에서는 `emulator-5554`에 설치한 각각의 APK가 모델을 실제 로딩하고 서버 기본 경로를 확인했습니다. TTS 준비는 Debug true / Benchmark false로 기록되어 음성 준비 상태를 구분합니다. 실제 처리 프레임은 0개이고 `field_acceptance_verified=false`입니다. 설치한 [Debug APK 사본](artifacts/jeonju/runs/20260919-004727-063-selftest/app-debug.apk)과 [Benchmark APK 사본](artifacts/jeonju/runs/20260919-004804-777-selftest/app-benchmark.apk), 정확한 SHA·검사·실패 복구 기록은 [인도 기록](docs/jeonju_demo_implementation_20260918.md#실행한-검사와-최종-갱신란)에 남겼습니다. `guidance_snapshot_dispatched`는 공통 revision 전달 기록이며 실제 렌더·발화 완료의 증거는 아닙니다.

계측 시작의 `0 tests / Process crashed` 두 차례는 원인 미확정 실패로 보존했습니다. 수동 설치·force-stop 뒤 직접 계측과 Gradle 계측에서 각각 7개를 소스 변경 없이 통과했습니다. 이 복구를 현장 통합 안정성 시험으로 해석하지 않습니다.

`guidance_render_submitted`는 실제 GL draw 제출과 revision을 기록하고 frame interval/작업 시간을 계측합니다. 화면 픽셀·보도 정합 완료와 구분하며 TTS도 session:revision별 시작/종료 callback을 별도로 남깁니다. runner의 약 30초 간격 memory/thermal/battery 수집은 10분 통합 수용 시험을 위한 계측이고 그 시험 자체의 완료 증거는 아닙니다.

```powershell
# 수집된 run과 별도로 검토한 평가 정답을 읽어 증거 평가
.\.venv\Scripts\python.exe scripts\assess_jeonju_run.py 'artifacts\jeonju\runs\<run_id>' --evaluation 'evaluation\C01.json' --output 'artifacts\jeonju\runs\<run_id>\assessment.json'
```

평가 파일은 `clip_id`, `expected_reroutes`, `expected_edge_ids`, `expected_labels`를 명시하고 인식·매핑 입력으로 사용하지 않습니다. 정답이 없으면 `--evaluation`을 생략하여 구조·연쇄·누락만 검사합니다. `verified_partial`이나 종료 코드 0은 전체 합격이 아니며 결과는 `whole_project_acceptance=not_issued`를 유지합니다. [평가 계약과 남은 수용 시험](docs/jeonju_demo_implementation_20260918.md#독립-실행-근거-평가)을 확인하세요.

## 데이터 반입·후보 생성

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev,geo]"
.\.venv\Scripts\python.exe scripts\import_jeonju_p0.py --archive '.\data\NaVi_Jeonju_P0_FINAL_20260918.zip'
.\.venv\Scripts\python.exe scripts\validate_jeonju_p0.py --output artifacts\jeonju\validation.json
.\scripts\fetch_ai_baseline_model.ps1
```

반입기는 ZIP CRC·42개 체크섬·CRS/좌표·OSM ID·revision을 확인하고 원본을 `data/raw/jeonju/p0/20260918/`에 보존합니다. 동봉 보행망 GeoJSON은 way 후보이며 런타임 Graph로 직접 대입하지 않습니다. `source=osm_user_handoff`와 접근성 미확인을 유지합니다. OSM 데이터는 OpenStreetMap contributors의 ODbL 조건을 따릅니다.

`scripts/build_jeonju_graph.py`는 필요한 태그·통행 조건·장벽/속성 변경점과 단위를 보존하는 **검토용 후보**를 `data/processed/jeonju/candidate_graph.geojson`에 만듭니다. 활성 P0 Graph를 덮어쓰는 경로는 거부합니다. 후보의 새 ID·길이는 고정 scope와 동등하지 않으므로 자동 활성화하지 않습니다. 이전 `prepare_jeonju_data.py`는 새 P0 ZIP 반입 명령이 아닙니다.

전국 횡단보도 CSV 50,000행 중 전주시 행은 0개이며 Graph에 병합하지 않았습니다. 로드뷰 metadata는 실제 이미지 분석 결과가 아니고 현장 실측 템플릿은 비어 있습니다. 실제 전주 DEM·수치지형도·정사영상이 없다는 이유로 내부 P0 구현을 막지 않습니다. 안양용 공간평가 및 후보 검토 자료는 legacy 진단이며 전주 경로 사실로 사용하지 않습니다.

## 백엔드·모듈

독립 서버가 필요하면 다음과 같이 실행합니다. 기본 DB는 전주 전용 `data/runtime/jeonju_p0_20260918.db`이며 runner는 run별 전용 DB를 만듭니다. 전주 Graph가 없거나 revision이 다르면 실패하며 sample Graph로 대체하지 않습니다.

```powershell
$env:NAVI_GRAPH_PATH = 'data/processed/jeonju_accessibility_graph.geojson'
$env:NAVI_DB_PATH = 'data/runtime/jeonju_p0_20260918.db'
.\.venv\Scripts\python.exe -m uvicorn app.main:app --app-dir backend --host 127.0.0.1 --port 8000
```

- AR 카메라·CPU 센서 입력: `:feature:ar-navigation`
- 모델 추론·프레임 수명: `:feature:ai-perception`
- 공간·지속성 판단과 경로 영향 매핑: `:feature:guidance-fusion`
- 공통 좌표·stamp·정합·관측·RouteSnapshot: `:core:guidance-contract`
- 자동 Live/Replay 및 녹화: `android/app/.../demo/`
- API: `GET /health`, `GET /demo/jeonju`, `POST /route`, `POST /route/compare`, `GET /route/sessions/{session_id}`, `POST /route/sessions/{session_id}/reroute`

현재 시연은 `demo_jeonju`와 실제 관측을 사용하는 자동 흐름입니다. 기존 수동 제보·공용 Graph 검수·안양 후보 시뮬레이션 화면과 API는 별도 legacy 기능이며 자동 시연 수용 증거에 합산하지 않습니다. 자동 회피는 사람 검수 승인을 기다리지 않지만 공용 Graph에 영구 반영할 사실은 별도 검수 대상입니다.

## 테스트와 참고 문서

```powershell
.\.venv\Scripts\python.exe -m pytest --junitxml=artifacts\jeonju\backend-all.xml
cd android
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:assembleBenchmark :app:testDebugUnitTest :core:guidance-contract:test :feature:guidance-fusion:test :feature:ai-perception:testDebugUnitTest :feature:ar-navigation:testDebugUnitTest --console=plain
```

- [전주 자동 시연 구현·인도 기록](docs/jeonju_demo_implementation_20260918.md): 요구사항별 증거, 실입력 형식, 실패 처리, 남은 수용 시험
- [프로젝트 마스터 계획](docs/project_master_plan.md): 현재 목표와 이전 M1/M2/합성 시험의 범위
- [Android 아키텍처](docs/android_architecture.md), [AR·AI 모듈화](docs/ar_ai_modularization_plan.md), [데이터 신뢰도](docs/data_trust_model.md)
- [25m 현장 정합 계획](docs/m1_local_field_route_jbnu.md), [기존 E2E-WC 계약 시험](docs/e2e_wc_jbnu_route.md)
- [이전 M1 AR 성능](docs/m1_ar_performance_baseline_20260918.md), [M1-VIS shadow 비교](docs/ar_ai_parallel_alignment_spike.md): 이번 AI·Depth·Scene Semantics 통합 성능으로 재사용하지 않음
- [legacy 공간평가](docs/spatial_data_evaluation_report.md), [legacy Graph 후보](docs/graph_enrichment_candidate_report.md): 안양용 진단이며 전주 실측·Graph 업데이트 근거가 아님

기본 Android 지도는 MapLibre/OSM을 사용합니다. Kakao JavaScript/REST 키는 선택적인 별도 웹·서버 기능용이며 Android 네이티브 지도 키가 아닙니다. 비밀키는 로컬 `.env`에만 보관합니다.
