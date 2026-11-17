CREATE TABLE IF NOT EXISTS web_push_subscription (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  member_id BIGINT NOT NULL,
  endpoint_hash VARCHAR(64) NOT NULL UNIQUE,
  endpoint VARCHAR(2048) NOT NULL,
  p256dh VARCHAR(512) NOT NULL,
  auth_secret VARCHAR(128) NOT NULL,
  FOREIGN KEY (member_id) REFERENCES member(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS web_push_pending (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  trip_id BIGINT NOT NULL,
  subscription_id BIGINT NOT NULL,
  phase VARCHAR(8) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  due_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (trip_id, subscription_id, phase),
  INDEX idx_web_push_due (due_at, id),
  FOREIGN KEY (trip_id) REFERENCES family_trip(id) ON DELETE CASCADE,
  FOREIGN KEY (subscription_id) REFERENCES web_push_subscription(id) ON DELETE CASCADE
);
