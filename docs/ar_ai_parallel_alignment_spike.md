# M1-VIS AR 기준선·AI 보정 병렬 정합 스파이크

상태: **M1-VIS-1 정적 비교 완료, M1-VIS-2 녹화 replay 대기**

기준일: 2026-09-18

목적: 현재 AR 경로 리본과 오픈소스 보도 분할 기반 보정 리본을 같은 입력에서 병렬 실행해 정확도·지연·발열을 직접 비교한다.

## 핵심 결정

두 방식을 대체 관계로 보지 않고 병렬 경로로 유지한다.

```text
동일 camera frame + route + pose/depth
        ├─ A. GEOMETRY_BASELINE ── 현재 GPS/heading/ARCore 리본
        └─ B. AI_ASSISTED_SHADOW ─ 보도 mask로 제한 보정한 리본
                                      ↓
                              side-by-side 비교/로그
```

- A는 항상 실행되는 기준선이자 안전 fallback이다.
- B는 먼저 `shadow` 출력만 만들며 사용자 안내, 재탐색, 공용 Graph를 바꾸지 않는다.
- ARCore가 카메라를 단독 소유하고 AI에는 timestamp가 있는 동일 CPU frame만 전달한다.
- AR 렌더링은 연속 실행하고 AI는 별도 worker에서 최대 5~10Hz로 최신 frame만 처리한다.
- AI 작업은 한 번에 하나만 허용하고 밀린 frame은 버린다.
- AI mask가 오래됐거나 신뢰도가 낮으면 즉시 A만 표시한다.

## 비교 대상

### A. 현재 기하 기반 AR

- 입력: route geometry, GPS, heading, ARCore pose/depth
- 장점: 결정론적이며 이미 실기기에서 안정성·성능 기준선을 확보했다.
- 한계: 화면에서 보도 경계를 인식하지 못해 GPS·heading 오차가 그대로 리본 위치에 반영될 수 있다.

현재 SM-S911N benchmark 기준:

| 지표 | 측정값 |
|---|---:|
| CPU 평균 | 70.829% |
| app frame 처리 평균 / p95 | 1.647ms / 3.179ms |
| 평균 PSS | 354,851KB |
| 1분 tracking/Depth | 전 표본 정상 |
| 20분 안정성 | crash·process death·fatal 0 |

### B. 오픈소스 AI 보정 AR

- 입력: A와 동일한 frame/route/pose/depth + 보도·도로 segmentation mask
- 1차 후보: Cityscapes 계열 경량 semantic segmentation 모델
- 역할: 보도 영역과 중심 방향을 관측하고 리본의 횡방향 위치를 제한적으로 보정한다.
- 금지: AI가 경로를 새로 만들거나 Edge를 차단하거나 공용 Graph를 수정하는 동작

공개 benchmark는 모델 선택 참고값일 뿐 SM-S911N 성능이나 전북대 현장 정확도가 아니다. PP-LiteSeg 공개값은 Snapdragon 855, 256×256, 단일 thread에서 STDC1 `77.04% mIoU / 17.22 FPS`, STDC2 `79.04% mIoU / 11.75 FPS`다. ARCore와 동시에 실행하는 실제 목표는 우선 5~10Hz로 제한한다.

## 실행 순서

### 1. 정적 거리 영상 A/B — 완료

- 동일한 거리 이미지와 동일한 route/시야 정보를 A와 B에 입력한다.
- 카카오 Roadview 또는 네이버 Panorama의 공식 viewer overlay로 방향 표시 UX를 확인한다.
- AI pixel 추론 입력은 로컬 시험 frame으로만 다루고 원본 이미지와 모델 파일은 Git에 넣지 않는다.
- 원본, A 리본, 보도 mask, B 리본을 한 화면 또는 한 report에서 비교한다.

이 단계는 보도 인식과 투영 로직을 확인하지만 GPS, ARCore tracking, Depth 정확도를 증명하지 않는다.

#### M1-VIS-1 구현 결과 — 2026-09-18

`scripts/run_m1_vis_comparison.py`는 한 정적 frame에서 A 리본과 외부 sidewalk mask 기반 B 리본을 같은 pixel 좌표계로 비교한다. 실제 AI 모델은 실행하지 않으며 `android/feature/ai-perception/**`도 수정하지 않았다.

입력 계약은 다음과 같다.

- frame: ID, 촬영 timestamp, 평가 timestamp, width/height, 선택적 로컬 이미지 경로
- A: pixel centerline과 ribbon half-width
- mask: 동일 frame ID·timestamp, width/height, confidence, 행별 sidewalk span
- policy: 최소 confidence `0.70`, 최대 mask age `300ms`, 최대 횡보정 pixel

mask 누락·크기 불일치·frame 불일치·미래 timestamp·stale·저신뢰·빈 sidewalk는 모두 `FALLBACK_A`가 되며 B 좌표는 A와 같아진다. 유효한 B도 `shadow`로만 기록하고 사용자 가시 안내는 계속 A다.

```powershell
.\.venv\Scripts\python.exe scripts\run_m1_vis_comparison.py `
  --input backend\tests\fixtures\m1_vis\synthetic_static_frame.json `
  --report artifacts\m1-vis\synthetic-golden\report.json `
  --svg artifacts\m1-vis\synthetic-golden\comparison.svg
```

JSON에는 A/B 보도 내부 비율, 경계 이탈 비율, 보도 중심 pixel offset, B 횡이동량, Gate 결과와 provenance를 기록한다. 단일 synthetic frame에는 시간축 흔들림과 정답 segmentation mask가 없으므로 jitter·IoU·Dice·pixel recall은 수치를 만들지 않고 `not_evaluated`로 기록한다.

golden fixture에서는 로직 검증용으로 보도 내부 비율이 A `0.0`에서 B `1.0`, 보도 중심 평균 offset이 `6px`에서 `3px`로 변했다. 이는 인위적으로 만든 mask의 예상값이며 실제 AI 정확도나 현장 개선 근거가 아니다. 생성 JSON·SVG와 로컬 원본 이미지는 `artifacts/m1-vis/`에만 두고 Git에서 제외한다.

### 2. 녹화 영상 replay A/B — 바로 다음 작업

- 기존 ARCore MP4/telemetry 또는 동일 계약의 녹화 frame을 사용한다.
- frame timestamp를 기준으로 pose/depth/route와 mask를 결합한다.
- 같은 입력 반복 실행의 mask와 보정 결과가 재현되는지 확인한다.

### 3. SM-S911N shadow 실행

- 화면에는 A를 유지하고 B는 debug overlay 또는 로그로만 출력한다.
- AR은 연속 렌더링하고 AI는 5Hz부터 시작해 여유가 확인될 때만 10Hz로 올린다.
- 1분 기준선 비교 후 10분 열 안정성 비교를 수행한다.
- B가 유효하지 않으면 A로 자동 복귀하는지 확인한다.

### 4. 전북대 내부 106 학생군사교육단 남측 25m 현장 3회

- A와 B를 같은 회차에서 동시에 기록한다.
- 현장 사람이 확인한 보도 경계와 리본 위치를 기준으로 상대 개선 여부를 판정한다.
- 현장 결과 전까지 B는 `shadow`, 전체 결과는 `pending`, `verified=false`다.

## 공통 측정 지표

| 범주 | 지표 |
|---|---|
| 분할 | sidewalk IoU, Dice, pixel recall, false-positive 면적 |
| 정합 | 리본의 보도 내부 비율, 경계 이탈 비율, 횡방향 오차 |
| 안정성 | frame 간 리본 흔들림, mask age, fallback 횟수·복구 시간 |
| 성능 | inference p50/p95, 처리 FPS, dropped frame, CPU, PSS |
| 열·전력 | 배터리 온도, AP/skin 온도, thermal status |
| 안전 | tracking loss, 사용자 가시 오류, 잘못된 보정 횟수 |

## 1차 통과 기준

- B가 A보다 보도 내부 리본 비율을 개선해야 한다. 절대 임계값은 첫 라벨 세트 결과 뒤 고정한다.
- AI inference p95 목표는 200ms 이하, 사용 mask age는 300ms 이하로 시작한다.
- 밀린 frame을 쌓지 않고 최신 frame 우선 정책이 유지돼야 한다.
- crash, process death, fatal exception과 AI 때문에 새로 발생한 unexpected tracking loss가 없어야 한다.
- 배터리 45°C 또는 thermal status 4 이상이면 시험을 중단한다.
- B가 실패하거나 stale이면 A가 계속 동작해야 한다.

## 병렬 작업 소유권

| 작업 | 소유 범위 |
|---|---|
| A 리본, panorama overlay harness, AR 성능 비교 | M1/AR 현재 작업 |
| 오픈소스 모델 선택·변환·mask 평가 | M2 별도 채팅 |
| 동일 timestamp 결합, A/B 선택과 stale/confidence gate | M3 `guidance-fusion` |

현재 M1 작업에서는 `android/feature/ai-perception/**`, `data/ai-evaluation/**`와 M2 평가기를 수정하지 않는다. M2가 `PerceptionObservation` 또는 segmentation mask fixture를 내보내면 M1-VIS harness가 소비하고, 실제 실시간 결합은 M3에서 수행한다.

## 판정

- A만 충분하면: 현재 AR + 2D fallback을 유지한다.
- B가 정확도를 높이고 성능 기준을 만족하면: AI 보정을 optional 경로로 채택한다.
- B가 좋아 보이지만 정량 근거가 없으면: 거리 영상 demo에만 유지한다.
- B가 발열·지연·오보정을 늘리면: 실시간 사용을 중단하고 offline 분석에만 사용한다.

## 참고

- 현재 성능 기준선: `docs/m1_ar_performance_baseline_20260918.md`
- 전북대 현장 절차: `docs/m1_local_field_route_jbnu.md`
- AR·AI 전체 경계: `docs/ar_ai_modularization_plan.md`
- 카카오 Roadview overlay: <https://apis.map.kakao.com/web/sample/roadviewImageOverlay/>
- 네이버 Panorama: <https://navermaps.github.io/maps.js.ncp/docs/tutorial-Panorama.html>
- PP-LiteSeg benchmark: <https://github.com/PaddlePaddle/PaddleSeg/blob/release/2.10/README_EN.md>
