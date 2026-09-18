# 전주 고정 코스 PoC 구현 및 시연 안내

> 후속 변경: 사용자가 지정한 동쪽 우회로·아래 합류점 도착·서버 없는 해커톤 모드·연속 AR 화살표는 [해커톤 지정 경로 모드](jeonju_hackathon_preset_20260919.md)를 따른다. 아래 내용은 이전 서버 기반 모드의 기록이다.

## 구현한 흐름

앱의 기본 진입은 `PocLive`다. 지정 출발점에서 남쪽 보행로를 바라보고 **시연 시작**을 한 번 누르면 현재 AR 위치와 방향을 코스에 연결한다. 위도·경도·기준점 두 점을 입력하지 않는다. 기존 `Live` 정밀 기준점 모드는 별도로 유지한다.

카메라 준비 화면에서도 콘 검출 박스를 표시한다. 시작 이후 실제 이미지와 센서로 콘의 위치·지속성·진행 경로와의 관계를 판단하고, 유효한 관측만 서버로 전달한다. 우회가 계산되면 동일한 경로 버전으로 지도·AR 띠·음성을 갱신한다. 코너에 접근하면 좌·우 회전 음성을 내고 도착·중단 시 기록을 저장한다. 추적을 잃으면 경로를 지우고 기록을 마친 뒤 출발점에서 다시 준비하도록 안내한다.

파란 띠는 현재 감지한 수평 바닥 높이에 놓는다. 경사·굴곡 추종은 이번 범위에서 제외했다. 공동 Graph와 접근성 검증 상태는 바꾸지 않는다.

## 초기 정렬과 거리 판단의 의미

`PocStartAlignment`는 출발점·방향을 사용자가 맞췄다는 가정하에 상대 이동을 코스 좌표에 대응시킨다. `MapCalibration`의 실측 기준점 정합과 다른 타입이다. 절대 위치 정확도는 `null`로 유지하며, 진행 판단에 사용하는 값은 별도의 `relative_tracking_budget_m`이다. 이 값은 구성한 허용 범위이며 실측 정확도가 아니다. 앱·서버·기록 파일에 `alignment_source=poc_start`를 기록한다.

서버는 지정 출발·도착점과 `demo_jeonju` 프로필에서만 이 모드를 시작한다. 정밀 세션과 PoC 세션의 관측 혼용, 임의 출발점, 측정 정확도를 기입한 상대 모드 요청은 거부한다. 세션 내 우회만 변경하며 상대 모드의 후보를 검증된 장애물로 승격하지 않는다.

유효한 Raw Depth가 부족하면 PoC의 콘에 한해 현재 인식한 수평면과 카메라 광선의 교차점으로 바닥 접점을 추정한다. 임의의 카메라 높이는 쓰지 않는다. 카메라-바닥 높이 0.4–2.5m, 하향 광선, 추정 거리 0.3–8m 등의 조건을 확인한다. 평면도 없으면 보류한다. 기록에는 `ground_plane_ray`와 `raw_depth`를 구분한다.

PoC의 보행 영역 검사는 SIDEWALK와 TERRAIN을 허용하되 경로의 공간적 일치와 지속 관측을 별도로 요구한다. ROAD 등은 허용하지 않는다. TERRAIN을 실제 통행 가능성의 검증으로 해석하지 않는다. ARCore 라벨 정의는 [공식 SemanticLabel 문서](https://developers.google.com/ar/reference/c/group/ar-semantic-label)를 확인했다.

## 기존 자료 검사에서 확인한 것

- 원본 5개, CPU 프레임 2,946장을 그대로 사용했다. 새 촬영은 요구하지 않았다.
- 실제 픽셀의 콘 후보는 129개 프레임, 총 143개 박스다. 정확도나 재현율 수치가 아니다.
- 실제 Depth·Semantics·AR pose를 사용한 진단에는 **가정한 초기 정렬**을 명시했다. 지리적 위치나 실제 경로 우회를 검증하는 Replay로 간주하지 않는다.
- 평면 보조 방식 추가 후에도 해당 녹화에서 2초 지속 조건까지 통과한 콘 관측은 **0개**다. 콘이 있는 129개 프레임의 마지막 판단 사유는 거리 근거 부족 65, 보행 영역 불확실 46, 정지 지속 확인 중 8, 추적·정렬 불가 8, 이동 판단 2였다. 따라서 기존 영상에서 자동 우회가 성공했다고 주장하지 않는다.
- 원시 GPS와 AR 동선의 강체 변환 대조 잔차는 파일별 약 1.07/0.73/0.95/1.47/1.29m였다. 같은 원시 GPS에 맞춘 잔차이며, 독립적인 지도 정합 정확도가 아니다. 원시 GPS의 보고 정확도보다 좋은 실측 정합으로 해석하지 않는다.
- 촬영 동선은 코스 주변을 포함하지만 촬영한 우회 전체가 지도상의 대체 경로와 동일하다고 확정하지 않았다. 앱의 우회 계산은 기존에 고정한 8개 보행 구간을 사용한다. 영상에서 새 도로를 생성하지 않았다.

실제 녹화에 대한 판정 로그는 `data/ai-evaluation/runs/jeonju-cone-20260919/spatial_diagnostic.tsv`, 코스 대조는 같은 경로의 `course_correspondence.json`에 있다. 원본 정합·검증 상태는 수정하지 않았다. 새 PoC 촬영 기록은 schema 4로 저장하며, 실측 정합용 schema 2 Replay로 읽히지 않는다.

## 시연자가 할 일

1. 설치한 NaVi를 열고 카메라·위치 권한과 한국어 음성을 준비한다.
2. 화면의 **지도에서 출발점 보기**로 `35.8463514, 127.1319861`에 해당하는 코스 시작점으로 간다. 사진은 출발 구간 참고이며 측량 표지가 아니다.
3. 보행로를 따라 남쪽(`35.8458917, 127.1319832` 방향)을 향한다. 앞쪽 바닥이 카메라에 보이도록 휴대폰을 들고 천천히 움직여 바닥과 주변을 인식시킨다.
4. 준비가 끝나면 **시연 시작**을 누른다. 시작 위치와 방향을 이때 고정한다. 이후 파란 띠와 음성에 따라 천천히 걷는다.
5. 우회는 콘 검출만으로 발생하지 않는다. 거리/평면·보행 영역·정지 지속·경로 위치 조건이 충족돼야 한다. 판단 보류 시 화면의 사유를 확인한다.
6. 도착하면 자동 저장된다. 중단할 때는 **종료하고 기록 내보내기**를 누른다. 추적 손실 후에는 출발점에서 다시 준비한다.

## 서버와 설치

현재 앱에 포함된 인터넷 주소는 `https://shopping-recommendation-emails-flow.trycloudflare.com`이며 인증 코드는 문서나 로그에 노출하지 않는다. 해당 주소를 통해 새 `poc_start` 기능 응답을 확인했다. 휴대폰의 USB 포트 포워딩은 필요하지 않지만 임시 터널·서버 PC가 실행 중이어야 한다. 영구 운영 서버는 아니다.

연결된 물리 S23이 없어 이번 APK의 S23 설치·현장 실행은 아직 수행하지 못했다. 에뮬레이터 검사는 파일 기록과 좌표 계약을 검사하는 용도이며 실제 카메라 AR의 현장 성능을 증명하지 않는다.

최종 검사 결과는 Python 181개 통과·2개 건너뜀, Android 단위 검사 119개 통과, 에뮬레이터 기록/좌표 어댑터 검사 9개 통과다. 어댑터 검사는 최초 실행에서 프로세스 종료가 발생했고 앱을 force-stop 후 재실행하여 통과했다. Debug/Benchmark APK와 instrumentation APK 빌드, Debug lint가 통과했다.

최종 서버 재시작 명령은 자동 승인 검토가 정책상 차단했다. 세부 이유는 제공되지 않았다. 명령이 실행되기 전에 차단되어 기존 서버는 유지되며 인터넷 응답 HTTP 200과 `poc_start` 지원을 다시 확인했다. 마지막에 추가한 비교 API를 통한 PoC 세션 시작 차단과 평면 높이의 boolean 입력 거부는 소스/테스트에는 있으나 현재 서버 프로세스에 반영되지 않았다. 이 두 항목의 배포 완료를 주장하지 않는다.

설치 파일: `artifacts/jeonju/runs/poc-start-delivery-20260919/NaVi-Jeonju-PoC.apk`.
SHA-256: `2664be8f598083e31accfa48fb97874c1a0f644e08dcad1ed3ef9f187642576c`.

## 재현

```powershell
.\.venv\Scripts\python.exe scripts/analyze_poc_course_alignment.py --input-root artifacts/jeonju/runs/field-intake-20260919 --output data/ai-evaluation/runs/jeonju-cone-20260919/course_correspondence.json
.\.venv\Scripts\python.exe -m pytest -q
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:NAVI_CONE_EVAL_DIR='C:\Project\ARNAVIGATION-JEONJU\data\ai-evaluation\runs\jeonju-cone-20260919'
$env:NAVI_COLLECTION_DIR='C:\Project\ARNAVIGATION-JEONJU\artifacts\jeonju\runs\field-intake-20260919'
.\android\gradlew.bat -p android :core:guidance-contract:test :feature:guidance-fusion:test :feature:ai-perception:testDebugUnitTest :feature:ar-navigation:testDebugUnitTest :app:testDebugUnitTest :app:assembleBenchmark :app:lintDebug
```

검증 결과와 설치 APK 해시는 `artifacts/jeonju/runs/poc-start-delivery-20260919/verification.json`에 정리한다. 물리 기기와 현장 항목은 실제 수행 전까지 미검증으로 남긴다.
