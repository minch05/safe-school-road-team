# HTTPS·휴대폰 위치 읽기 사전 시험

RGN6-54 전용 시험 페이지. 프로젝트 회원·DB·API 키와 연결되지 않으며, 현재 좌표는 브라우저 화면에서만 표시한다. 서버는 두 정적 파일만 제공한다. 위치 결과·좌표를 수집하는 API는 없다.

## 실행

저장소 루트에서 Node.js로 실행한다.

```sh
node experiments/https-location/server.mjs
```

다른 터미널에서 기존 시험용 cloudflared를 실행한다.

```sh
experiments/iphone-push/cloudflared tunnel --no-autoupdate --url http://127.0.0.1:8097
```

출력된 HTTPS 주소를 휴대폰 Safari 또는 Chrome에서 연다. 터널과 서버가 실행 중일 때만 접속할 수 있다. 종료는 각 터미널에서 Ctrl+C. 터널 주소는 재실행 때 바뀐다.

## 실기기 절차

1. HTTPS 연결 정상과 위치 API 지원을 확인한다.
2. 단말·OS·브라우저를 입력하고 현재 위치 한 번 읽기를 누른다.
3. 위치 권한을 허용하고 성공 여부·정확도를 확인한다. 결과 내려받기로 좌표 없는 JSON을 보관한다.
4. 브라우저의 이 사이트 위치 권한을 차단한 뒤 다시 시도한다. 거부 안내가 표시되는지 확인하고 별도 결과를 보관한다.
5. 권한을 다시 허용하고 재시도하여 복구 여부를 확인한다.
6. 가능한 경우 iOS Safari·Android Chrome을 각각 시험한다. 없는 단말은 미시험으로 기록한다.

실제 현재 위치가 읽히는지와 오류 안내를 검증하는 사전 시험이다. 서비스의 지도·로그인·길 안내 전체 배포와 현장 보행 정확도 검증은 포함하지 않는다. 자동 시험의 합성 위치를 실기기 성공으로 기록하지 않는다.

## 참고

- https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/
- https://developer.mozilla.org/en-US/docs/Web/API/Geolocation/getCurrentPosition
