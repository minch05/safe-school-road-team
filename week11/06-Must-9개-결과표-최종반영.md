# 2026-11-11 Must 9개 1차 시험 결과표 · 유준서 기록 반영본

- **시험일:** 2026-11-11 (개별 시험 시각 전체 미기록)
- **기록자:** 유준서
- **원격 main 조회 SHA:** `901e15b2acd5c640e97410666f6ad374ee39085f` (로컬 실행 SHA 미확인)
- **서버 주소:** `http://localhost:8081`
- **DB:** 기본 H2 설정으로 기동; 별도 `coordinate_verify` H2 테스트 실행. MySQL 시험 미실시
- **TMAP:** 실경로/모의 API 통합 시험 미실시 (로컬 지도 인증 실패 화면)
- **기기/브라우저:** Windows / Chrome, 실기기 모바일 미사용
- **판정:** 통과 / 부분 / 실패 / 미실시

| 요구 | 시험 절차와 통과 조건 | 확인 담당 | 실제 결과 및 판정 | 증거 또는 보류 이유 |
|---|---|---|---|---|
| FR-01 가입·로그인 | 가입 201, 중복 409, 로그인, 자동 로그인 유무, 로그아웃 뒤 보호 GET 401 | 강태 | **미실시** (해당 시나리오 전체) | 웹 화면에 로그인 상태는 확인했으나 201·409·401 및 재접속 시험은 실행·기록 없음. 강태 확인 필요 |
| FR-02 출발·도착 선택 | 검색·지도 지점 두 곳 선택과 후보 요청 좌표 일치 | 동한 | **미실시** | 화면의 좌표 입력란은 보였으나 지점 선택·요청 본문 확인 없음 |
| FR-04 후보 경로 | TMAP 후보 1~3개, 실패 메시지, 거리·시간 | 민채 | **미실시** | 로컬 지도 인증 실패 메시지 확인, 실제 TMAP 후보 생성은 시험하지 않음 |
| **FR-05 경로 근거** | 횡단·신호 추정/미확인·시설 근거를 응답/자료와 대조 | **준서** | **부분** — `EvidenceAnalyzerTests` Gradle **성공**; 합성 경로의 건넘·시설 중복·안내 지점·누락 처리 등 계산 로직 검증 | `.\gradlew.bat test --tests '*EvidenceAnalyzerTests' --rerun-tasks` → `BUILD SUCCESSFUL in 3s`, `4 actionable tasks: 4 executed`. **실 TMAP `/api/routes/evidence` 응답과 실제 후보 경로의 연결 검증은 미실시** |
| FR-06 비교 | 후보 ID별 선·카드·근거 연결, 후보 1개·자료 부족 표시 | 동한 | **미실시** | 후보 비교 화면·연결 시험 증거 없음 |
| FR-08 확정·저장 | 저장 201, 본인 목록·상세, 다른 계정 404, 지점 없음 422 | 민채·강태 | **미실시** | 해당 API·권한 시험 증거 없음 |
| FR-09 안내문 | 검증 문장 저장·재조회, AI 실패 시 기본 문장과 출처 | 민채 | **미실시** | 안내문 실서버 시험 증거 없음 |
| **FR-15 공공자료** | 12개 구 적재 현황, 구별 수, 좌표·출처 표본 | **준서** | **부분** — 12개 구 × 6종 **원본↔coverage 72/72 일치**; 시설 **69,514건**, 사고 면 자료 **47개**; 중랑구·강서구 별도 H2 좌표·ID·출처 자동 검사 성공 | `/api/data/coverage` 화면, `district-counts.md`; `.\gradlew.bat test --tests '*DistrictCoordinateVerificationTests' --rerun-tasks` → `BUILD SUCCESSFUL in 7s`. **실행 서버 `facility` SQL COUNT 직접 집계는 미실시** |
| FR-19 휴대폰 길 안내 | 저장 ID로 실행, HTTPS 실기기 위치 허용·거부, 선 실패 처리 | 동한·민채 | **미실시** | HTTPS 모바일 실기기 시험·권한 허용/거부 결과 없음 |

## FR-05 구체적 검증 항목 (자동 시험만)

- **건넘 신호 처리:** ESTIMATED/UNKNOWN/UNMATCHED 구분 검증.
- **시설:** 가까운 CCTV 중복 제거, 비상벨·학교·어린이보호구역·사고 다발 지역 계산 검증.
- **안내 지점:** TMAP 건넘 좌표 그대로가 아니라 공공자료 시설 좌표와 원본 ID 사용.
- **제약사항:** 보안등 자료 부족, 신호 미확인과 출처 표시; 안전 점수 제공하지 않음.
- **자료 미존재:** 시설 근거 없음이 '안전하다'는 의미로 바뀌지 않도록 검증.
- 위 항목은 GitHub의 `EvidenceAnalyzerTests` 기존 시험 로직 기준이며 **실제 TMAP 경로 응답 시험 결과로 기재하지 않는다**.

## FR-15 자료 대조 근거

| 분류 | 확인 내용 | 결과 |
|---|---|---|
| 원본↔적재 현황 | 12개 구 × 6종 = 72항목 | 72항목 모두 일치, 0 불일치 |
| 적재 시설 | 횡단보도 24,216, 학교 593, 어린이보호구역 711, CCTV 30,051, 안전비상벨 13,943, 보안등 0 | 시설 합계 69,514건 |
| 보안등 | 12개 구 모두 원본 및 coverage 0건 | NO_DATA · '자료 부족' (실제 시설 없음으로 해석 금지) |
| 사고 면 데이터 | 보호구역/어린이/노인 보행자 사고 다발 지역 | 구별 원본 합계 47개; 서버 적재 로그 47개 |
| 표본 | 중랑구 CROSSWALK `06-0000019003` / 강서구 CROSSWALK `06-0000019294` | 원본 범위 이내·별도 H2 JUnit 통과 |
| 검증 한계 | `district-counts.md`의 'DB 행 수' 출처는 coverage.json | `facility` 직접 SQL 집계 미실시 |

## 실패·보류 기록

| 실패/보류 | 재현 방법 | 기대 결과 | 실제 결과 | 담당/Jira | 다음 확인 조건·날짜 |
|---|---|---|---|---|---|
| FR-05 실경로 통합 확인 보류 | 로그인 후 실제 TMAP 후보 생성 → `/api/routes/evidence` 요청 → candidateId와 시설 원본 대조 | 후보와 근거의 ID·선·횡단·출처 일치 | **미실시** (지도 인증 실패 화면; 실 TMAP 경로 입력·응답 미수집) | 준서 / RGN6-131, 민채 협조 | 실제 TMAP 키가 설치된 팀 시험 환경에서 재시험, ~2026-11-17 |
| FR-15 직접 SQL 건수 확인 보류 | 테스트 DB에서 `scripts/district_counts.sql` 실행 | `facility` 직접 집계 12개 구 × 자료 종류별 원본과 일치 | **미실시** (coverage 값 비교만 완료) | 준서 / RGN6-130 | 시험 DB 읽기 권한 확보 후 대조, ~2026-11-17 |
| FR-01/02/04/06/08/09/19 팀 시험 보류 | 팀 합동 시험 체크리스트 순차 실행 | 각 요구사항 충족 | **미실시** (담당자별 실행 기록 없음) | 표의 담당자 / RGN6-131 | 팀원 기록 병합, ~2026-11-17 |

## 정상 흐름에서 마지막으로 확인된 단계

- **로컬 서버 실행 → 로그인된 웹 화면 접속 → `/api/data/coverage` JSON 조회**까지 확인.
- 출발·도착 경로 요청부터 저장·휴대폰 안내까지의 **끝단 통합 흐름은 검증하지 않음**.

## 증거 파일·명령

```powershell
.\gradlew.bat bootRun --args='--import-supported-data'
python scripts/district_counts.py --coverage-json build/coverage.json --out build/district-counts
.\gradlew.bat test --tests '*SupportedDataImportTests' --tests '*FacilityNearbyRepositoryTests' --tests '*EvidenceAnalyzerTests'
.\gradlew.bat test --tests '*DistrictCoordinateVerificationTests' --rerun-tasks
.\gradlew.bat test --tests '*EvidenceAnalyzerTests' --rerun-tasks
```

- 사용자 생성 `district-counts.md` 및 사용자 제공 Gradle·브라우저·서버 로그 화면.
- PR 미생성. **본인 브랜치에 결과표를 이관해 커밋하기 전까지 제출 완료 상태가 아니다.**
- `.env`, API 키, 세션/쿠키, 개인정보, `build/` 폴더는 PR에 포함하지 않는다.
