CREATE TABLE IF NOT EXISTS member (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  login_id VARCHAR(50) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  nickname VARCHAR(60) NOT NULL,
  age_14_or_older BOOLEAN NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS persistent_logins (
  username VARCHAR(64) NOT NULL,
  series VARCHAR(64) PRIMARY KEY,
  token VARCHAR(64) NOT NULL,
  last_used TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS facility (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  source VARCHAR(120) NOT NULL,
  original_id VARCHAR(120) NOT NULL,
  kind VARCHAR(40) NOT NULL,
  district VARCHAR(40) NOT NULL,
  lat DOUBLE NOT NULL,
  lng DOUBLE NOT NULL,
  source_downloaded DATE NOT NULL,
  source_reference VARCHAR(200) NOT NULL,
  name VARCHAR(100) NOT NULL DEFAULT '',
  signal_status VARCHAR(20),
  base_date DATE,
  UNIQUE (source, original_id),
  INDEX idx_facility_kind_district (kind, district),
  INDEX idx_facility_lat_lng (lat, lng)
);
CREATE TABLE IF NOT EXISTS risk_area (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  source VARCHAR(120) NOT NULL,
  original_id VARCHAR(120) NOT NULL,
  kind VARCHAR(40) NOT NULL,
  district VARCHAR(40) NOT NULL,
  name VARCHAR(200) NOT NULL DEFAULT '',
  base_year INT,
  min_lat DOUBLE NOT NULL,
  min_lng DOUBLE NOT NULL,
  max_lat DOUBLE NOT NULL,
  max_lng DOUBLE NOT NULL,
  polygon_json LONGTEXT NOT NULL,
  UNIQUE (source, original_id),
  INDEX idx_risk_area_box (min_lat, max_lat, min_lng, max_lng)
);
CREATE TABLE IF NOT EXISTS data_coverage (
  district VARCHAR(40) NOT NULL,
  kind VARCHAR(40) NOT NULL,
  rows_loaded INT NOT NULL,
  status VARCHAR(20) NOT NULL,
  source VARCHAR(120) NOT NULL,
  source_downloaded DATE,
  PRIMARY KEY (district, kind)
);
CREATE TABLE IF NOT EXISTS saved_route (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  member_id BIGINT NOT NULL,
  name VARCHAR(30) NOT NULL,
  start_lat DOUBLE NOT NULL,
  start_lng DOUBLE NOT NULL,
  end_lat DOUBLE NOT NULL,
  end_lng DOUBLE NOT NULL,
  candidate_id VARCHAR(20) NOT NULL,
  search_option INT NOT NULL,
  point_count INT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_saved_route_member (member_id),
  FOREIGN KEY (member_id) REFERENCES member(id)
);
CREATE TABLE IF NOT EXISTS saved_route_point (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  route_id BIGINT NOT NULL,
  seq INT NOT NULL,
  kind VARCHAR(40) NOT NULL,
  name VARCHAR(100) NOT NULL DEFAULT '',
  reason VARCHAR(20) NOT NULL,
  lat DOUBLE NOT NULL,
  lng DOUBLE NOT NULL,
  along_m INT NOT NULL,
  signal_status VARCHAR(20),
  source VARCHAR(120) NOT NULL,
  original_id VARCHAR(120) NOT NULL,
  guide_text VARCHAR(300) NOT NULL,
  guide_source VARCHAR(20) NOT NULL,
  UNIQUE (route_id, seq),
  FOREIGN KEY (route_id) REFERENCES saved_route(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS api_cache (
  cache_key VARCHAR(64) PRIMARY KEY,
  api VARCHAR(40) NOT NULL,
  body LONGTEXT NOT NULL,
  cache_day DATE NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_cache_day (cache_day)
);
CREATE TABLE IF NOT EXISTS api_usage (
  usage_day DATE NOT NULL,
  api VARCHAR(40) NOT NULL,
  calls INT NOT NULL,
  PRIMARY KEY (usage_day, api)
);
