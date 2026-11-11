-- 구별 DB 적재 행 수 (RGN6-110 보조자료). 결과를 district,kind,rows CSV로 저장해 district_counts.py --db-csv 로 넘긴다.
-- MySQL 예: mysql -B -e "source scripts/district_counts.sql" safe_school_road | tr '\t' ',' > db-counts.csv
SELECT district, kind, COUNT(*) AS `rows`
FROM facility
GROUP BY district, kind
ORDER BY district, kind;

-- 참고: 사고 다발 지역(면 자료) 구·종류별 개수
-- SELECT district, kind, COUNT(*) FROM risk_area GROUP BY district, kind ORDER BY district, kind;
-- 참고: 서버가 기록한 적재 현황(자료 부족 포함)
-- SELECT district, kind, rows_loaded, status, source, source_downloaded FROM data_coverage ORDER BY district, kind;
