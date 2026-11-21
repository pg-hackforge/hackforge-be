-- 방어 검증과 해답, 실습 완료 (ADR 025)

-- 방어 검증 결과. 공격 검증 행에서는 비어 있다
ALTER TABLE verifications
    ADD COLUMN security_passed        BOOLEAN,
    ADD COLUMN functional_passed      BOOLEAN,
    ADD COLUMN security_message       VARCHAR(500),
    ADD COLUMN functional_message     VARCHAR(500),
    ADD COLUMN defense_hints_revealed SMALLINT CHECK (defense_hints_revealed BETWEEN 0 AND 3),
    ADD COLUMN after_solution         BOOLEAN;

-- 첫 방어 검증 PASS 시각과 해답을 처음 연 시각
ALTER TABLE lab_sessions
    ADD COLUMN completed_at       TIMESTAMPTZ,
    ADD COLUMN solution_viewed_at TIMESTAMPTZ;
