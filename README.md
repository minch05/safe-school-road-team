# 초등학생 통학 안전·안내 서비스 — 팀 저장소

Java 21 · Spring Boot 3.5.6 · Gradle 8.14.3. 가입·로그인, 실제 TMAP 후보, 지원 지역 공공자료 적재, 후보별 근거 분석, 경로 저장·재조회와 안내문 생성이 구현돼 있습니다. 후보 카드는 근거·저장 API 연결 작업 중이며 길 안내 화면은 뼈대 단계입니다.

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
| 후보 근거 | `POST /api/routes/evidence` | `start,end`로 후보별 공공자료 근거 조회 |
| 저장 | `POST /api/routes` | `start,end,candidateId,name`; 201의 `id`·`guideSource` 사용 |
| 저장 경로 | `GET /api/routes`, `/{id}`, `/{id}/line` | 본인 경로만 조회, 다른 계정 404, 오늘 경로선 재요청 |
| 자료 현황 | `GET /api/facilities/status`, `/sample` | 적재된 횡단보도 건수·범위와 중랑구 일부 미리보기 |
| 상태 | `GET /health/setup` | 서버 기동 여부. DB 접속과 외부 API 성공을 보증하지 않음 |

TMAP 옵션 0·4·10을 요청하고 **전체 경로선이 동일할 때만** 후보를 병합합니다. 외부 응답은 `api_cache`에 한국 날짜 기준 **당일만** 보관하며 이전 날짜 데이터는 정시 삭제합니다. `api_usage`에 실제 외부 호출 시도를 기록하므로 당일 캐시 조회는 호출 수를 늘리지 않습니다. 지도선은 서비스의 영구 경로 데이터로 저장하지 않습니다. 백업/제출물에 `api_cache`를 포함하지 마세요.

## 공공자료 범위와 한계

`--import-crosswalk-sample`을 지정한 경우에만 `src/main/resources/sample/crosswalks-jungnang-10.json`을 적재합니다. 원본은 서울시 교통안전시설물 횡단보도 OA-15554 / `trafficSafetyA004AInfo`, 다운로드 날짜는 2026-10-02입니다. 원본 EPSG:5186의 XCRD/YCRD를 WGS84로 변환하고 `source + original_id` 중복을 막습니다. **중랑구 개발용 표본 10건이지 서울 12개 구의 정식 적재가 아닙니다.** 다운로드 날짜는 시설 현장 확인일이 아닙니다. 신호 여부·횡단 안전도·경로 주변 전체 시설 수를 이 표본으로 판정하지 않습니다.

지원 12개 구 자료는 `--import-supported-data`로 적재합니다. 종류별 부족 자료와 신호 추정·미확인은 근거 응답에서 구분합니다.

## 검증 상태

`./gradlew test bootJar`와 JavaScript 문법 확인을 수행합니다. 분리된 MySQL 8에서 표본 10건·2회 적재 중복 없음, 가입 201/중복 409·세션 로그인, 실제 TMAP 후보 2개, 네이버 검색 5개, 지도 인증 실패 시 VWorld 보조 지도 표시를 확인했습니다. 이 결과는 **개발용 시험**입니다. HTTPS 배포 URL, 실제 휴대폰, 전 구 공공자료, 팀원 코드 인수, 보고서 제출은 별도 확인이 필요합니다.

## Git·Jira

이 코드는 민채의 선행 구현입니다. 다른 팀원의 최초 구현으로 기록하지 않습니다. Jira 백로그 변경은 교수님 검수 전까지 로컬 초안으로만 둡니다. `.env`, 키, 개발 DB, TMAP 캐시 응답, 사용자 위치는 Git에 올리지 않습니다.

## AI 안내문과 HTTPS 운영 설정

`GUIDE_AI_ENABLED=true`와 `GROQ_API_KEY`를 설정하면 경로를 저장할 때 Groq 안내문을 요청합니다.
기본 모델은 `openai/gpt-oss-20b`이며 `GUIDE_AI_MODEL`로 지정합니다.
보내는 입력은 순번·시설 종류·그 지점 이름·선정 이유·신호 상태·마지막 여부·기본 문장입니다.
응답의 개수·순번·장소·신호·횡단 행동·방향·안전 단정 표현을 검사합니다.
요청 연결은 3초, 응답 읽기는 8초까지 기다리며 자동 재호출은 하지 않습니다.
키 없음·호출 비활성·오류·시간 초과·검증 실패 시 기본 문장으로 저장합니다.
저장 응답과 상세의 `guideSource`는 실제 저장 문장에 맞춰 `AI` 또는 `TEMPLATE`가 됩니다.

기본 실행은 로컬 개발용 HTTP입니다. 운영에서는 `prod` 프로필과 전용 MySQL 연결,
충분히 긴 고유한 `REMEMBER_ME_KEY`를 사용합니다.
`prod`는 DB 접속 환경변수와 자동 로그인 키를 필수로 요구하고 세션 쿠키에 Secure를 설정합니다.
HTTPS를 앱에서 직접 제공할 때는 서비스 도메인에 발급한 PKCS12 인증서 파일과 비밀번호를
`TLS_KEY_STORE`·`TLS_KEY_STORE_PASSWORD`로 지정하고 `prod,https`를 함께 사용합니다.
인증서와 환경 파일은 저장소 밖에 보관합니다.

```sh
java -jar build/libs/safe-school-road-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod,https
```

`SERVER_ADDRESS` 기본값은 `127.0.0.1`입니다. 배포할 서버의 수신 주소와 실제 HTTPS 포트는
배포 환경에서 `SERVER_ADDRESS`·`PORT`로 지정합니다.
인증서가 서비스 도메인과 맞는지, 지도 제공자의 허용 도메인이 등록됐는지 확인합니다.
최초 시험 DB는 `--import-supported-data`로 적재합니다.
실제 도메인 배포와 휴대폰 위치 허용·거부 결과는 별도로 확인해야 합니다.
