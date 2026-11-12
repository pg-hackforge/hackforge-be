-- reconciler와 API가 같은 세션을 동시에 바꿀 때 나중에 저장하는 쪽을 실패시키는 낙관적 잠금(JPA @Version)
ALTER TABLE lab_sessions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
