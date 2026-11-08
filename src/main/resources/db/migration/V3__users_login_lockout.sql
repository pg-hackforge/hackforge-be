-- 로그인 실패 잠금(ADR 011): 연속 실패 횟수와 잠금이 풀리는 시각
ALTER TABLE users ADD COLUMN failed_login_count INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN locked_until TIMESTAMPTZ;
