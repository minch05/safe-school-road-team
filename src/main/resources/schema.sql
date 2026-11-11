-- 자동 로그인(remember-me) 토큰. Spring Security가 정한 모양 그대로다.
-- JPA 엔티티가 아니라서 ddl-auto 로는 안 생기므로 여기서 만든다. 이미 있으면 그대로 둔다.
CREATE TABLE IF NOT EXISTS persistent_logins (
    username  VARCHAR(64) NOT NULL,
    series    VARCHAR(64) PRIMARY KEY,
    token     VARCHAR(64) NOT NULL,
    last_used TIMESTAMP   NOT NULL
);

-- 공공데이터 시설 (FR-15). 핵심 지점·위험요소 집계가 여기서 시설을 찾는다.
-- 좌표 검색에 MySQL 공간 인덱스를 써야 해서 JPA 엔티티가 아니라 여기서 만든다.
-- location 은 위도·경도(WGS84, SRID 4326). 넣을 때 'axis-order=long-lat' 으로 경도·위도 순서를 정해 준다.
CREATE TABLE IF NOT EXISTS facility (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    kind          VARCHAR(20)  NOT NULL,              -- CROSSWALK, SCHOOL, CCTV, EMERGENCY_BELL
    name          VARCHAR(100) NOT NULL DEFAULT '',   -- 정해진 항목으로 만든 이름 (HANDOFF 5-3 "시설 이름 저장")
    lat           DOUBLE       NOT NULL,
    lng           DOUBLE       NOT NULL,
    location      POINT        NOT NULL SRID 4326,
    signal_status VARCHAR(20)  NULL,                  -- 횡단보도만: YES(있음) / ESTIMATED(있음 추정) / UNKNOWN(미확인)
    district      VARCHAR(10)  NOT NULL DEFAULT '',   -- 자치구 이름. 주소에서 못 찾으면 빈칸
    source        VARCHAR(40)  NOT NULL,              -- 어느 데이터에서 왔는지
    source_id     VARCHAR(40)  NOT NULL,              -- 원본 관리번호 (이력 중복 제거·다시 적재용)
    base_date     DATE         NULL,                  -- 데이터 기준일
    imported_at   DATETIME     NOT NULL,
    UNIQUE KEY uk_facility_source (source, source_id),
    KEY idx_facility_kind_district (kind, district),
    SPATIAL INDEX idx_facility_location (location)
);

-- 외부 API 당일 캐시 (HANDOFF 5-1, 8장). TMAP 응답은 약관상 24시간 넘게 둘 수 없어
-- 오늘(한국 시간) 만든 것만 쓰고, 날짜가 바뀌면 지운다(TmapCache 의 정리 작업).
CREATE TABLE IF NOT EXISTS api_cache (
    cache_key  VARCHAR(64) PRIMARY KEY,   -- 요청 내용의 SHA-256
    api        VARCHAR(20) NOT NULL,
    body       MEDIUMTEXT  NOT NULL,
    created_at DATETIME    NOT NULL,
    KEY idx_api_cache_created (created_at)
);

-- 외부 API 하루 호출 수 (TMAP 하루 1,000건 한도를 넘지 않게)
CREATE TABLE IF NOT EXISTS api_usage (
    day   DATE        NOT NULL,
    api   VARCHAR(20) NOT NULL,
    calls INT         NOT NULL,
    PRIMARY KEY (day, api)
);

-- 영역으로 나오는 공공데이터 (사고다발지역 등). 경로가 영역을 지나는지 본다.
CREATE TABLE IF NOT EXISTS risk_area (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    kind        VARCHAR(30)  NOT NULL,    -- CHILD_ACCIDENT_SCHOOLZONE 등
    name        VARCHAR(100) NOT NULL DEFAULT '',
    area        GEOMETRY     NOT NULL SRID 4326,   -- Polygon 또는 MultiPolygon
    area_json   MEDIUMTEXT   NOT NULL,    -- 화면에 그리고 코드로 판정하기 위한 GeoJSON
    district    VARCHAR(10)  NOT NULL DEFAULT '',
    source      VARCHAR(40)  NOT NULL,
    source_id   VARCHAR(40)  NOT NULL,
    base_year   INT          NULL,
    imported_at DATETIME     NOT NULL,
    UNIQUE KEY uk_risk_area_source (source, source_id),
    SPATIAL INDEX idx_risk_area_area (area)
);

-- 비로그인 경로 살펴보기(FR-17) 하루 횟수: 기기당 3회 (HANDOFF 5-7). 기기는 브라우저 쿠키의 무작위 번호로 구분한다
CREATE TABLE IF NOT EXISTS guest_quota (
    day    DATE        NOT NULL,
    device VARCHAR(64) NOT NULL,
    calls  INT         NOT NULL,
    PRIMARY KEY (day, device)
);
