-- prod(validate) 배포 전 백업 후 한 번 적용. 이미 적용된 DB에서는 실행하지 않는다.
-- 기존 기록/좌표를 변경하지 않는다. NULL인 기존 요청에는 중복 제한을 적용하지 않는다.
ALTER TABLE trip ADD COLUMN start_request_id VARCHAR(36) NULL,
    ADD CONSTRAINT uk_trip_start_request UNIQUE (start_request_id);
