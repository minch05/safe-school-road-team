-- 기존 push_subscription 테이블에 한 번 적용한다. 기존 행은 NULL로 유지 가능.
ALTER TABLE push_subscription ADD COLUMN registration_generation VARCHAR(36) NULL;
