# 전주 실증용 인터넷 연결 — 2026-09-19

Galaxy S23 (`R3CWA0J3XRZ`)용 앱의 기본 연결을 USB reverse에서 HTTPS로 변경했다.

- 현재 복구 주소: `https://shopping-recommendation-emails-flow.trycloudflare.com`
- 최초 설치 APK의 주소 `https://passing-cursor-discovered-shirts.trycloudflare.com`는 이전 터널 주소다. 앱의 **서버 연결 설정**에서 현재 주소로 변경해야 한다. 접속 코드는 동일하다.
- 흐름: Android → HTTPS Cloudflare 임시 터널 → `127.0.0.1:8018` 접속 코드 검사 → `127.0.0.1:8000` 전주 API.
- 휴대폰에는 인터넷 연결이 필요하다. PC의 전주 서버·게이트웨이·터널도 켜져 있어야 한다.
- 이는 PC와 독립된 클라우드 서버 배포가 아니다. PC 절전·종료 또는 터널 종료 시 연결이 끊긴다.
- Cloudflare Quick Tunnel은 개발/시연용이며 재실행하면 주소가 바뀐다. 고정 주소와 상시 운영은 별도 배포가 필요하다.

## 앱 사용

설치된 NaVi 아이콘을 열면 미리 넣은 주소와 접속 코드를 사용한다. 시연을 시작하지 않은 상태에서 **서버 연결 설정**을 열 수 있다. 새 주소와 코드를 입력하고 **확인 후 저장**을 누르면 서버의 지역·데이터·범위·Graph 해시를 앱의 기준과 비교한다. 검사가 성공해야 설정이 저장되고 준비 과정이 다시 시작된다. 실패하면 기존 설정을 유지한다.

설정은 앱 전용 저장소에 보관한다. 테스트 스크립트의 `backend_url` intent는 해당 실행에 우선 적용되며 자동 저장되지 않는다. HTTP 주소도 기존 로컬 개발을 위해 허용하지만 접속 코드는 HTTPS에서만 보낸다. 인증 헤더를 다른 주소로 넘기지 않도록 HTTP 리다이렉트를 따르지 않는다.

## 공개 범위와 비밀값

외부 게이트웨이는 접속 코드가 있는 다음 요청만 전달한다.

- `GET /health`, `GET /demo/jeonju`
- `POST /route`: `demo_jeonju` 프로필만 허용
- `POST /route/sessions/{id}/reroute`: 전주 재탐색 계약 필수

Graph 수정, 관측 검수, 웹 UI, API 문서는 공개하지 않는다. 요청 본문은 64 KiB로 제한한다. 실제 계약 값과 세션별 우회 검증은 기존 백엔드가 수행한다.

`artifacts/jeonju/field-server/access-token.local.txt`와 `android/app/src/main/assets/field_connection.local.json`은 Git에서 제외한다. 현장용 APK에는 임시 접속 코드가 포함되므로 개인 시연 기기에만 설치한다. 이는 단일 시연팀을 위한 접속 제한이며 사용자별 인증 체계가 아니다. APK와 개인 프로비저닝 파일을 공개 배포하지 않는다.

## 재시작

현재 실행 중인 서버를 중복 실행하지 않는다. 재시작이 필요하면 저장소 루트의 PowerShell 세 창에서 각각 실행한다.

```powershell
# 1. 전주 서버
$env:NAVI_GRAPH_PATH = Join-Path $PWD 'data\processed\jeonju_accessibility_graph.geojson'
$env:NAVI_DB_PATH = Join-Path $PWD 'data\runtime\jeonju_device_R3CWA0J3XRZ_20260919_install.db'
.\.venv\Scripts\python.exe -m uvicorn app.main:app --app-dir backend --host 127.0.0.1 --port 8000
```

```powershell
# 2. 접속 제한 게이트웨이: 기존 접속 코드를 재사용한다.
.\scripts\run_jeonju_field_gateway.ps1
```

```powershell
# 3. 임시 HTTPS 주소: 현재 PC에서는 UDP/QUIC 연결이 실패하여 HTTP/2를 사용한다.
.\artifacts\jeonju\field-server\cloudflared.exe tunnel --url http://127.0.0.1:8018 --protocol http2 --no-autoupdate
```

새 주소는 앱의 **서버 연결 설정**에서 바꾼다. 접속 코드는 유지된다. 초기 설치용 기본값을 바꾸려면 Git에서 제외한 `field_connection.local.json`의 `url`도 갱신한 뒤 APK를 다시 빌드한다. 사용자가 저장한 주소는 APK 기본값보다 우선한다.

Cloudflared `2026.9.1`을 공식 GitHub 릴리스에서 내려받고 릴리스 자산의 SHA-256과 비교했다. 출처와 해시는 `artifacts/jeonju/field-server/cloudflared-source.json`에 있다.

## 확인한 범위

- 게이트웨이 자동 테스트: 12개 통과.
- Android 앱 단위 테스트: 30개 통과, debug lint 성공.
- Benchmark APK 빌드와 S23 업데이트 설치 성공.
- 호스트에서 공개 HTTPS 주소로 전주 bootstrap 및 130.7m 초기 경로 응답 확인. 이는 통신 확인용 세션이며 실제 주행 기록이 아니다.
- 접속 코드 없는 요청은 401, `tcp:18018` ADB reverse 제거 후 목록이 비어 있음을 확인.
- 사용자가 S23에서 서버 오류 없이 기준점 준비 화면이 열린 것을 확인했다. 외부 클라이언트의 bootstrap 200 응답과 기기의 `Live` 실행 `20260919-012400-618-2661b993`의 `model_loaded` 기록도 확인했다. 모델 로드는 bootstrap 데이터 대조 이후 수행된다. 확인 시 ADB reverse 목록은 비어 있었다.
- 실제 접속망이 LTE·5G인지 Wi-Fi인지는 별도로 확인하지 않았다. 이 결과가 기준점 실측과 실제 AR·AI 실증 완료를 의미하지 않는다.

원시 연결 확인 및 설치 파일은 `artifacts/jeonju/field-server/`에 보관한다. 이전 전체 구현 검증 요약을 이번 네트워크 시험 결과로 대체하지 않는다.

## 2026-09-19 연결 장애 확인 및 복구

01:49 KST 점검에서 백엔드 8000, 게이트웨이 8018의 리스너와 cloudflared 프로세스가 모두 없었다. 휴대폰의 설정 화면에서도 이전 주소에 대한 연결 실패가 확인됐다. PC의 마지막 부팅은 9월 16일이었으며 프로세스 종료 원인은 확인되지 않았다.

백엔드와 터널을 숨김 백그라운드 프로세스로 다시 시작했다. 게이트웨이의 백그라운드 실행은 도구 자동 승인 검토가 차단했으며 상세 사유는 제공되지 않았다. 게이트웨이는 기존 `run_jeonju_field_gateway.ps1`을 임시 전경 프로세스로 실행했다. 상시 서비스 또는 자동 재시작을 설정한 상태는 아니다.

새 터널 주소를 공용 DNS `1.1.1.1`에서는 조회할 수 있었지만 PC의 기본 DNS에서는 NXDOMAIN이 발생했다. 조회된 IP를 curl `--resolve`로 지정하고 정상 TLS 인증서 검증을 유지한 상태에서 공개 HTTPS bootstrap 200과 전주 데이터 계약 일치를 확인했다. 시스템 DNS 설정은 변경하지 않았다. 당시 휴대폰의 활성 네트워크는 CELLULAR였다.

복구 결과와 원시 진단 자료는 `artifacts/jeonju/field-server/connection-diagnosis/`에 저장한다. 앱의 새 주소 저장 및 재접속 확인 결과는 해당 폴더의 `recovery.json`에 별도로 기록한다.

01:53 KST에 S23의 서버 연결 설정에서 새 주소를 입력하고 **확인 후 저장**을 실행했다. 실제 기기 화면에서 새 서버 URL과 "두 기준점에서 정합을 준비한 뒤 시작하세요" 메시지를 확인했다. 새 `Live` 실행 `20260919-015348-183-f56b36f9`에서 `model_loaded`가 기록됐으며 ADB reverse 목록은 비어 있었다. 기존 APK를 재설치하지 않고 앱에 저장한 서버 주소만 변경했다.

공식 문서: [Cloudflare Quick Tunnels](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/), [Cloudflared 다운로드](https://developers.cloudflare.com/tunnel/downloads/).
