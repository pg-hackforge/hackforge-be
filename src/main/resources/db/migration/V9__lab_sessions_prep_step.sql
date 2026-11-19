-- 실습 환경 준비 단계 (ADR 021). CREATING, INITIALIZING에서만 값이 있고 나머지 상태는 NULL
ALTER TABLE lab_sessions
    ADD COLUMN prep_step VARCHAR(12) CHECK (prep_step IN ('SPACE', 'CONTAINER', 'LOADING', 'STARTING'));

-- 진행 중 세션은 지금 상태의 첫 단계로 둔다. INITIALIZING은 다음 reconcile 주기에 로그를 읽어 앞으로 간다
UPDATE lab_sessions SET prep_step = 'SPACE' WHERE status = 'CREATING';
UPDATE lab_sessions SET prep_step = 'CONTAINER' WHERE status = 'INITIALIZING';
