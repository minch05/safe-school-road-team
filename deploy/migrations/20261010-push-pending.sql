-- 운영 ddl-auto=validate 실행 전에 적용. 사용자 좌표/구독 인증키는 저장하지 않는다.
CREATE TABLE IF NOT EXISTS push_pending (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 recipient_id BIGINT NOT NULL,
 guardian_link_id BIGINT,
 trip_id BIGINT,
 trip_event VARCHAR(255),
 subscription_id BIGINT NOT NULL,
 subscription_stamp VARCHAR(64) NOT NULL,
 title VARCHAR(100) NOT NULL,
 body VARCHAR(500) NOT NULL,
 url VARCHAR(200) NOT NULL,
 expires_at DATETIME(6),
 next_attempt_at DATETIME(6),
 attempts INT NOT NULL,
 INDEX idx_push_pending_due (next_attempt_at)
);
