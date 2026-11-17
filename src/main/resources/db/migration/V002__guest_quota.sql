CREATE TABLE IF NOT EXISTS team_guest_quota (
  quota_day DATE NOT NULL,
  device_id VARCHAR(36) NOT NULL,
  calls INT NOT NULL,
  PRIMARY KEY (quota_day, device_id)
);
