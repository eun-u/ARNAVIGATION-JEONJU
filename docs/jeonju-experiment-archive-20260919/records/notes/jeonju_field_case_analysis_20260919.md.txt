# 현장 영상 사례 선별 및 인식 기록 분석

2026-09-19 반입한 S23 수집 ZIP 5개를 분석했다. **C02와 C03 검토 후보를 추출했고, 기존 지원 객체를 이용하는 C01은 미확보**로 판단했다. 모든 장면 판정은 assistant의 시각적 검토이며 사람이 검수한 정답으로 승격하지 않았다. 원본, 앱의 판단 기준, Graph는 변경하지 않았다.

## 선별 영상

원본 영상 시각으로 지정한 검토용 구간이다. 미리보기에는 원본 영상 시각을 표시했다. 원본 ARCore 센서 트랙을 포함하는 정밀 Replay 묶음이 아니며, 30fps로 재인코딩한 보기용 영상이다. 원본 CPU 프레임 ID·시각은 `previews/preview_manifest.json`과 각 `source_frames.jsonl`에 보존했다. 영상 시각과 collection elapsed 사이의 관계를 독립적으로 실측한 것은 아니다.

| 용도 | 원본 파일의 시작 시각 | 구간 | 판정과 다음 용도 |
|---|---|---|---|
| [공사 구간 진단](../data/ai-evaluation/runs/jeonju-field-review-20260919/previews/C01_construction_diagnostic.mp4) | 02:23:41 | 45–85초 | 공사 중 지면·구덩이·콘. 기존 클래스 기반 C01로 계산하지 않음. 장애물 지원 범위 검토 자료 |
| [C02 후보](../data/ai-evaluation/runs/jeonju-field-review-20260919/previews/C02_vehicle_outside_candidate.mp4) | 02:42:04 | 20–50초 | 차도 쪽 차량과 보행로의 분리가 보임. 차량의 정지 여부와 실제 지도 경로상 점유는 미확정 |
| [C03 후보](../data/ai-evaluation/runs/jeonju-field-review-20260919/previews/C03_passing_person_candidate.mp4) | 02:34:30 | 70–100초 | 약 77–86초에 사람이 다가와 지나간 뒤 공간이 비워짐. 불필요한 재탐색 억제 평가 후보 |
| [손·그림자 오류 진단](../data/ai-evaluation/runs/jeonju-field-review-20260919/previews/D01_self_shadow_negative.mp4) | 02:47:19 | 145–175초 | 촬영자 신체 일부와 그림자 등을 외부 통행 장애물과 구분할 때 활용 |

원본 ZIP 5개는 무결성 검사 및 전체 MP4 디코딩을 통과했다. 4개는 180초 자동 완료, 1개는 44초 중단 저장이다. 이번에 생성한 미리보기 4개도 전체 디코딩과 프레임 수 일치를 검사했다. 선택된 원본 CPU 프레임은 각각 151·131·109·100개다.

## 무엇을 실제로 검사했는가

- 2,946개 CPU 샘플의 촬영 당시 모델 출력 1,437개를 전수 집계했다. **새 모델 추론을 실행한 결과는 아니다.**
- 전체 동선을 5초 간격의 153개 프레임으로 검토했다. 그 사이 모든 장면을 연속 시청했다고 주장하지 않는다.
- 현재 지원 클래스 및 신뢰도 0.6 이상을 만족하는 검출 57개를 모두 표시해 검토했다. 신뢰도가 낮거나 검출되지 않은 객체의 부재를 증명하지 않는다.
- 고양이로 검출된 사례 중 각 영상에서 시간상 분산된 4개씩, 총 20개를 검토했다. 추가로 C02 주변을 0.5~1초 간격으로 확인했다.
- 후보·검출 검토에는 모두 `verified=false`, `human_verified=false` 또는 이에 해당하는 미검수 상태를 유지했다. 앱의 1분 단위 안내 C01/C02/C03를 장면 정답으로 사용하지 않았다.

## 인식 결과

현재 fusion 지원 클래스는 person, bicycle, car, motorcycle, bus, truck이며 신뢰도 문턱은 0.6이다. 정책 소스의 해시는 `analysis.json`에 기록했다.

| 종류 | 저장된 검출 수 | 신뢰도 0.6 이상 | 해석 |
|---|---:|---:|---|
| person | 212 | 47 | 외부 사람뿐 아니라 촬영자 신체 일부·그림자·가로등 등을 포함 |
| car | 583 | 10 | 4개는 차도 쪽 차량이 보임. 다른 6개는 작은 원거리 상이라 보수적으로 미확정 |
| bicycle | 5 | 0 | C01을 뒷받침할 높은 신뢰도 검출 없음 |
| motorcycle | 3 | 0 | 동일 |
| truck | 6 | 0 | 동일 |
| bus | 0 | 0 | 동일 |
| cat | 379 | 129 | 검토한 20개 표본은 바닥·계단·그림자 등으로 보임. 현재 회피 지원 클래스에서는 제외됨 |

표의 개수는 **검출 박스 수**로, 객체 개체 수나 정확도가 아니다. 한 프레임에 여러 검출이 있을 수 있다. 전수 정답이 없어 precision/recall은 산출하지 않았다.

신뢰도 0.6 이상 지원 검출 57개에 대한 시각적 검토 결과:

- 외부 사람으로 보이는 검출: 31개, 모두 C03 후보 구간.
- 촬영자 신체 일부: 10개. 신체 인식 자체의 오류와는 구분하되 외부의 통행 방해 객체가 아님.
- 그림자·가로등·나무 등 배경의 사람 오검출: 5개.
- 차도 쪽 차량: 4개.
- 모호하여 판정을 보류한 검출: 7개(사람 1, 차량 6).

이 수치는 assistant의 이미지 검토 기록이며 검수된 정답 기반 성능 지표가 아니다. 개별 frame_id와 검토 상태는 `reviewed_detections.json`에서 확인할 수 있다.

### C03 신뢰도 변화

두 번째 파일의 76.9–86.5초에는 원본 샘플 36개가 있다. 해당 구간에서 person 검출이 기록된 프레임 수는 신뢰도 0.35 이상 34개, 0.6 이상 31개, 0.75 이상 16개, 0.8 이상 11개다. 이는 정답 기반 재현율이 아니다. 문턱만 올리면 실제 통과 장면의 검출도 크게 줄어들기 때문에 이번 분석만으로 전역 문턱을 변경하지 않았다.

### 센서·실행 기록

- 2,916/2,946프레임에 추적·Depth·Semantics 기록이 있다. 존재 여부를 공간 측정 정확도로 해석하지 않는다.
- 객체 탐지 호출 시간의 파일별 p95는 63–85ms이다. 전처리·파일 저장·공간 판단·서버·화면 갱신을 포함한 지연이 아니다.
- 기록된 CPU 샘플의 중앙 간격은 약 200–204ms이고 500ms 초과 간격은 전체에서 5회다. 이는 렌더 FPS가 아니다.
- GPS 위치 오차 기록은 3.79–100m 범위이고 **지도 정합이 기록된 프레임은 0개**다. 모든 원본은 `calibration_status=pending`, `replay_eligible=false`다.
- 야간 흔들림·역광·렌즈 가림이 일부 표본에서 보인다. 조도 중앙값은 4개 파일에서 0lx, 44초 파일에서 약 1lx다. 조도 수치만으로 오류의 단일 원인을 확정하거나 영상을 전부 폐기하지 않는다.

## 현재 데이터로 가능한 것과 다음 단계

1. **C02/C03 후보와 오검출 자료를 재사용한다.** 이번 미리보기·원본 프레임으로 검토할 수 있다. 최종 라벨을 확정하기 전까지 후보로 유지한다.
2. **C01은 추가 확보가 필요하다.** 현재 모델로 PoC를 이어가려면 지원 객체가 실제 진행 공간을 지속 점유하는 장면이 필요하다. 자전거 등 동일 물체를 경로 안/밖에서 비교하면 C01/C02 통제 조건을 맞추기 좋다. 공사장 콘·구덩이를 핵심 대상으로 삼으려면 별도의 인식·지면 위험 판단 기능과 평가 자료가 필요하므로 기존 지원 대상으로 통과한 것처럼 처리하지 않는다.
3. **현장 재방문 전에 기준점 준비를 끝낸다.** 지도와 연결할 실측 두 점의 위치·오차 및 현장 식별 방법을 확보하고, 정밀 시연 모드의 AR 등록을 확인한 뒤 측정한다. OSM 경로 좌표나 이번 GPS 값을 실측 기준점으로 대체하지 않는다.
4. **정합 이후에 자동 판단을 검증한다.** C01 회피, C02/C03의 불필요한 회피 억제, 현재 위치 기준 재탐색 및 지도·AR·음성 갱신을 실제로 실행해야 한다. 이번 원본 수집은 해당 판단을 실행하지 않았다. 정합이 없어 판단을 못 하는 상태를 C02/C03 억제 성공으로 계산하지 않는다.

장면 선별을 위한 기존 영상 재촬영은 우선 필요하지 않다. 다만 정밀 Replay/현장 시연의 최종 수용에는 정합을 갖춘 수집 또는 검증 가능한 기존 자료의 정합 근거가 추가로 필요하며, 이번 검토용 영상 추출로 충족되지는 않는다.

## 산출물과 재현

- [전체 통계](../data/ai-evaluation/runs/jeonju-field-review-20260919/analysis.json)
- [모든 검출 CSV](../data/ai-evaluation/runs/jeonju-field-review-20260919/detections.csv)
- [장면 후보와 원본 구간](../data/ai-evaluation/runs/jeonju-field-review-20260919/scene_candidates.json)
- [지원 검출 57개 검토](../data/ai-evaluation/runs/jeonju-field-review-20260919/reviewed_detections.json)
- [미리보기 원본 추적·해시](../data/ai-evaluation/runs/jeonju-field-review-20260919/previews/preview_manifest.json)

원본 해시와 프레임 통계 확인은 다음 명령으로 재현한다. 통계만 산출할 때 추가 패키지는 필요하지 않다.

```powershell
.\.venv\Scripts\python.exe scripts/analyze_jeonju_collections.py --input-root artifacts/jeonju/runs/field-intake-20260919 --output data/ai-evaluation/runs/jeonju-field-review-20260919
```

이미지 표본 생성은 위 명령에 `--media-tools artifacts/jeonju/runs/field-intake-20260919/tools`를 추가한다. 로컬 분석 도구는 Pillow 12.3.0 / PyAV 18.1.0이며 앱 모델·의존성은 변경하지 않았다.

```powershell
.\.venv\Scripts\python.exe scripts/export_jeonju_review_clips.py --input-root artifacts/jeonju/runs/field-intake-20260919 --selection data/ai-evaluation/runs/jeonju-field-review-20260919/scene_candidates.json --output data/ai-evaluation/runs/jeonju-field-review-20260919/previews --media-tools artifacts/jeonju/runs/field-intake-20260919/tools
```

검토용 미디어와 파생 센서 파일은 Git 제외 경로에 보관한다. 원본 ZIP, 원본 calibration/verified 상태, Graph와 설치된 APK는 이번 작업에서 변경하지 않았다.
