# 10주차 산출물 (곽민채)

| Jira | 산출물 | 파일 |
|---|---|---|
| RGN6-126 지원 지역 공공자료 적재 | 적재 코드·면 자료 테이블 | `facility/SupportedDataImporter`, `schema.sql`(`risk_area`·`data_coverage`) — PR #6 |
| | 지원 지역 적재 기록 | [20261110_지원지역적재기록_v1.md](20261110_지원지역적재기록_v1.md) |
| RGN6-110 경로별 근거 분석 API | API 코드 | `evidence/*` — PR #6 |
| | 근거 분석 응답 예시 | [examples/20261110_근거분석응답예시_v1.json](examples/20261110_근거분석응답예시_v1.json), [적재 현황 응답](examples/20261110_적재현황응답예시_v1.json) |
| | 구별 건수 추출 스크립트·결과표 틀 | [20261110_구별건수추출스크립트·결과표틀_v1.md](20261110_구별건수추출스크립트·결과표틀_v1.md), `scripts/district_counts.py`·`.sql`, [결과표](20261110_구별건수결과표_v1.md) |
| RGN6-127 경로 확정·저장·재조회 API와 기본 안내문 | 코드 | `saved/*` — PR #6 |
| | 저장 경로 응답 예시 | [examples/20261110_저장경로응답예시_v1.json](examples/20261110_저장경로응답예시_v1.json) |
| | 팀원용 보조자료 | [20261110_팀원용보조자료_v1.md](20261110_팀원용보조자료_v1.md), `static/guide.html`·`js/guide.js`·`js/guide-geo.js`, `scripts/api_smoke.sh` |

응답 예시는 실제 적재 공공자료 + TMAP 모의 경로선(서울망우초등학교 주변)으로 만든 것이다. 다시 만들기:
`./gradlew test --tests '*ResponseExampleTests' -Dexamples.out=docs/week10/examples`
