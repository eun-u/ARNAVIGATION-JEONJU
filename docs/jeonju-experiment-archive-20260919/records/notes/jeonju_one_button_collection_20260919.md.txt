# 전주 사례 수집: 버튼 한 번으로 촬영·기록·저장

앱 기본 화면을 **NaVi 사례 수집**으로 변경했다. **사례 수집 시작**을 누르면 최초 Android 권한 요청을 제외하고 좌표 입력, 케이스 선택, 파일명 입력, 녹화 종료 조작 없이 진행된다.

1. 카메라·저장 공간을 확인하고 위치·조도·기기 회전 센서 및 객체 인식 모델을 준비한다.
2. 서버의 전주 데이터 버전을 확인한다. 연결 실패나 버전 불일치는 기록하며, 기기에 포함된 데이터 버전으로 원본 수집을 계속한다. 경로 요청이나 Graph 수정은 하지 않는다.
3. 60초씩 세 구간을 자동 진행하고 화면·한국어 음성으로 촬영 동작을 안내한다.
4. 180초 후 녹화를 종료하고 파일 해시와 요약을 만든다. S23에서는 **내 파일 → 다운로드 → NaVi → NaVi-collect-….zip**에 자동 저장한다.

한국어 TTS를 사용할 수 없으면 화면 안내로 진행한다. 화면 자동 꺼짐을 방지하며, 앱을 다른 화면으로 전환하거나 **중단하고 여기까지 저장**을 누르면 중단 사유와 그때까지의 자료를 저장한다. 위치 권한 거부·위치 수신 실패·조도 센서 미지원·Depth나 Semantics 부재는 누락 상태로 보존한다. AR 카메라가 30초 안에 준비되지 않거나 녹화 중 프레임 입력이 15초 이상 끊기면 무한 대기하지 않는다.

## 촬영 안내와 판정 경계

| 시간 | 안내 구간 | 행동 |
|---|---|---|
| 0–60초 | C01용 앞쪽 보행로 | 천천히 걷고, 멈춰 있는 지원 객체가 보이면 안전한 거리에서 비춘다. |
| 60–120초 | C02용 길 가장자리 | 길 밖에 있는 자전거·차량 등이 보이면 화면에 담는다. |
| 120–180초 | C03용 지나가는 사람 | 안전한 곳에 멈춰 앞쪽 보행로를 촬영한다. 사람이 없어도 녹화를 유지한다. |

위 시간창은 **촬영 안내의 구분**이다. 객체나 사람이 실제로 존재했다는 정답을 자동 생성하지 않는다. `case_labels_verified=false`이며 각 케이스는 `review_status=pending`이다. 대상이 없었던 장면도 원본으로 보존한다. 화면의 완료는 촬영·저장 절차 완료를 뜻하며 실제 C01~C03 충족이나 현장 수용 시험 통과를 뜻하지 않는다.

기존 정밀 Live 시연은 설정의 **기준점 정합을 사용하는 정밀 시연**으로 들어갈 수 있다. 해당 모드의 두 실측 기준점 및 정합 조건은 유지했다. 기존 실행 스크립트의 명시적인 `Live`, `Replay`, `SelfTest` intent도 유지한다.

## 저장 구조

원본: 앱 외부 전용 저장소 `jeonju/collections/collect-<시각>-<고유번호>/`.

- `recording.mp4`: ARCore 실제 녹화. 마이크 음성 녹음 권한은 요청하지 않는다.
- `frames.jsonl`, `images/`: 실제 샘플링한 CPU 원본 프레임, 프레임 ID, 시각, 회전, 카메라 내부 파라미터와 AR 좌표계 pose.
- `depth/`, `semantics/`: 센서 입력이 있는 프레임의 측정값·신뢰도·시각. 누락 시 만들어 채우지 않는다.
- 각 프레임의 `collection`: 촬영 안내 시간창, 실제 모델 검출 결과, GPS/네트워크 위치·오차·출처·mock 여부·측정 나이, 조도, 회전 벡터.
- `capture_state.json`, `collection_summary.json`: 중단/완료 이유, 센서별 수집량, 서버 상태, 케이스별 프레임 수와 검토 상태.
- `clip_manifest.json`: 파일 무결성과 센서 영상 형식 검사를 통과한 원본 묶음. 손상/불완전 자료는 정상 manifest를 발행하지 않고 원본과 오류를 보관한다.
- `ar_telemetry.csv`: ARCore 진단 기록.

원본은 자동으로 서버에 업로드하지 않는다. 내보낸 ZIP은 기존 파일을 덮어쓰지 않는 고유 이름을 사용한다. Android 29 이상에서는 MediaStore Downloads에 쓰고, 이전 버전에서는 앱 외부 전용 `jeonju/exports/`에 저장한다. ZIP 생성에 실패하면 원본을 유지하고 **보관한 기록 저장**으로 다시 내보낼 수 있다.

## 원본 수집과 정밀 Replay의 구분

새 묶음은 `schema_version=3`, `capture_purpose=case_collection`, `frame_calibration_policy=unregistered_ARCore_world`를 사용한다. GPS는 `raw_device_location_not_AR_calibration`이며 AR의 지도 좌표나 실측 기준점으로 대체하지 않는다. `calibration_status=pending`, `replay_eligible=false`, `field_acceptance_verified=false`이다.

기존 정밀 Replay의 schema 2 수용 조건을 완화하지 않았다. schema 3 원본을 그대로 정밀 Replay 성공 증거로 사용하는 요청은 거부된다. 원본 확인 명령은 다음과 같다.

```powershell
.\.venv\Scripts\python.exe .\scripts\validate_jeonju_collection.py <압축을 해제한 수집 폴더>
```

이 검사는 파일 무결성, 시각·케이스 시간창·센서 파일 크기와 정합 미확인 표시를 검사한다. 호스트에서는 MP4 디코딩이나 실제 현장 촬영 여부를 판정하지 않는다. Android 저장 과정은 MP4의 영상 트랙과 길이를 검사한다.

## 검증

앱 단위 테스트 33개, AR 모듈 단위 테스트 13개, Python 원본/정밀 클립 검사 테스트 38개, S23 계측 어댑터 테스트 8개가 통과했고 Benchmark 빌드와 debug lint가 성공했다. 테스트용 데이터는 실제 현장 사례로 합산하지 않는다.

2026-09-19 S23(`R3CWA0J3XRZ`)에서 사용자가 시작한 `collect-20260919-020510-730-669305c3` 실행은 촬영 안내와 남은 시간 표시 후 `user_stopped`로 종료됐다. 실제 수집 110프레임 모두 위치·조도 기록이 있었고, 102프레임에 추적·Depth·Semantics 기록이 있었다. 앱은 `Download/NaVi/NaVi-collect-20260919-020510-730-669305c3.zip`(81,189,600바이트)을 저장했으며, 기기에서 가져와 압축 해제한 자료가 `raw_collection_integrity_passed`를 받았다. ZIP SHA-256은 `a056ec6fa8f96f2cfa04044a4e51dc9eb68a260b4bd45b55118f1d1d2debec72`이다.

이 실행은 첫 안내 구간만 포함한다(C01 110, C02 0, C03 0). `capture_workflow_completed=false`이며 3분 전체 자동 종료의 실기기 검증이나 실제 C01~C03 장면 충족 증거로 사용하지 않는다. 기기 완료 화면과 원본 자료는 Git에서 제외된 `artifacts/jeonju/runs/collection-smoke-20260919/`에 보관한다. GPS 위치 정합과 사례 정답은 계속 `pending`이다.

참조: [Android 공유 저장소](https://developer.android.com/training/data-storage/shared/media), [ARCore 녹화·재생](https://developers.google.com/ar/develop/java/recording-and-playback/developer-guide).
