# 초등학생 통학 안전·안내 서비스 — 팀 저장소

Java 21 · Spring Boot 3.5.6 · Gradle 8.14.3. 이 소스에는 **9주차 첫 흐름**(가입/로그인, 횡단보도 개발용 표본 적재, 지도 입력/검색, 실제 TMAP 보행 후보 1~3개)이 구현돼 있습니다. 경로 저장·위험 근거 비교·AI 안내·GPS 길 안내는 후속 기능이며, 현재 후보 카드는 안전 점수를 제공하지 않습니다.

## 빠른 실행

```sh
cp .env.example .env
# .env의 키와 DB 연결을 본인 개발 환경에 맞게 입력; Git에 올리지 않음
./gradlew test bootJar
./gradlew bootRun --args='--import-crosswalk-sample'
```

브라우저에서 `http://localhost:8081`을 엽니다. `.env`는 실행 폴더에서 선택적으로 읽습니다. `TMAP_APP_KEY`가 없으면 실제 경로를 만들지 않고 오류를 표시합니다. 지도는 네이버가 기본이고, SDK 인증 실패 또는 키 미설정 시 VWorld 보조 지도로 전환합니다. VWorld도 없으면 좌표 입력으로 API 계약만 시험할 수 있습니다. 네이버 지도/검색의 허용 도메인은 공급자 콘솔에서 확인해야 합니다.

`DB_URL`을 설정하지 않으면 재시작 시 사라지는 H2 개발 DB를 사용합니다. 팀 DB 시험에는 `compose.yaml`의 MySQL 8 또는 승인된 별도 MySQL 8을 사용하고, `.env`에 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 설정합니다. 기존 3306 DB가 있으면 Docker를 중복 실행하지 마세요. `REMEMBER_ME_KEY`도 배포 전에 고유한 값으로 바꾸세요.

## 이번 주 API

| 기능 | 메서드/URL | 확인할 것 |
|---|---|---|
| 보안 토큰 | `GET /api/auth/csrf` | POST의 응답 `headerName`/`token` 사용 |
| 회원가입 | `POST /api/auth/signup` | JSON `loginId,password,nickname,age14OrOlder`; 201/400/409, 14세 미만 직접 가입 차단. 생년월일은 받지 않음 |
| 로그인·로그아웃 | `POST /api/auth/login`, `POST /api/auth/logout` | URL form `username,password,remember-me`; 세션/CSRF |
| 세션 | `GET /api/auth/me` | 인증 상태와 로그인 ID |
| 장소 검색 | `GET /api/places/search?q=` | 네이버 기본, VWorld 보조; 결과가 없으면 지도/좌표로 직접 선택 |
| 후보 | `POST /api/routes/candidates` | JSON `{start:{lat,lng},end:{lat,lng}}`; 로그인/CSRF 필요; `candidateId,distanceM,durationSec,line` |
| 자료 현황 | `GET /api/facilities/status`, `/sample` | 개발용 중랑구 횡단보도 10건, 출처·원본 내려받은 날짜 표시 |
| 상태 | `GET /health/setup` | 서버 기동 여부. DB 접속과 외부 API 성공을 보증하지 않음 |

TMAP 옵션 0·4·10을 요청하고 **전체 경로선이 동일할 때만** 후보를 병합합니다. 외부 응답은 `api_cache`에 한국 날짜 기준 **당일만** 보관하며 이전 날짜 데이터는 정시 삭제합니다. `api_usage`에 실제 외부 호출 시도를 기록하므로 당일 캐시 조회는 호출 수를 늘리지 않습니다. 지도선은 서비스의 영구 경로 데이터로 저장하지 않습니다. 백업/제출물에 `api_cache`를 포함하지 마세요.

## 공공자료 범위와 한계

`--import-crosswalk-sample`을 지정한 경우에만 `src/main/resources/sample/crosswalks-jungnang-10.json`을 적재합니다. 원본은 서울시 교통안전시설물 횡단보도 OA-15554 / `trafficSafetyA004AInfo`, 다운로드 날짜는 2026-10-02입니다. 원본 EPSG:5186의 XCRD/YCRD를 WGS84로 변환하고 `source + original_id` 중복을 막습니다. **중랑구 개발용 표본 10건이지 서울 12개 구의 정식 적재가 아닙니다.** 다운로드 날짜는 시설 현장 확인일이 아닙니다. 신호 여부·횡단 안전도·경로 주변 전체 시설 수를 이 표본으로 판정하지 않습니다.

## 검증 상태

`./gradlew test bootJar`와 JavaScript 문법 확인을 수행합니다. 분리된 MySQL 8에서 표본 10건·2회 적재 중복 없음, 가입 201/중복 409·세션 로그인, 실제 TMAP 후보 2개, 네이버 검색 5개, 지도 인증 실패 시 VWorld 보조 지도 표시를 확인했습니다. 이 결과는 **개발용 시험**입니다. HTTPS 배포 URL, 실제 휴대폰, 전 구 공공자료, 팀원 코드 인수, 보고서 제출은 별도 확인이 필요합니다.

## Git·Jira

이 코드는 민채의 선행 구현입니다. 다른 팀원의 최초 구현으로 기록하지 않습니다. Jira 백로그 변경은 교수님 검수 전까지 로컬 초안으로만 둡니다. `.env`, 키, 개발 DB, TMAP 캐시 응답, 사용자 위치는 Git에 올리지 않습니다.
