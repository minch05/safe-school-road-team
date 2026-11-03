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
  UNIQUE (source, original_id),
  INDEX idx_facility_kind_district (kind, district)
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
