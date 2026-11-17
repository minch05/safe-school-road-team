CREATE TABLE IF NOT EXISTS family_link (
  child_id BIGINT PRIMARY KEY,
  guardian_id BIGINT NOT NULL,
  link_type VARCHAR(8) NOT NULL,
  INDEX idx_family_guardian (guardian_id),
  FOREIGN KEY (child_id) REFERENCES member(id) ON DELETE CASCADE,
  FOREIGN KEY (guardian_id) REFERENCES member(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS family_invite (
  guardian_id BIGINT PRIMARY KEY,
  token VARCHAR(32) NOT NULL UNIQUE,
  expires_at TIMESTAMP NOT NULL,
  FOREIGN KEY (guardian_id) REFERENCES member(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS family_trip (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  child_id BIGINT NOT NULL,
  guardian_id BIGINT NOT NULL,
  route_id BIGINT,
  request_id VARCHAR(36) NOT NULL,
  started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  arrived_at TIMESTAMP,
  UNIQUE (child_id, request_id),
  INDEX idx_family_trip_child (child_id, started_at),
  FOREIGN KEY (child_id) REFERENCES member(id) ON DELETE CASCADE,
  FOREIGN KEY (guardian_id) REFERENCES member(id) ON DELETE CASCADE,
  FOREIGN KEY (route_id) REFERENCES saved_route(id) ON DELETE SET NULL
);
