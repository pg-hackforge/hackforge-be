-- 공격 검증과 힌트 (ADR 023)

-- 학습 단계. ATTACK에서 시작해 공격 검증 PASS나 건너뛰기로 DEFENSE가 된다
ALTER TABLE lab_sessions
    ADD COLUMN stage          VARCHAR(7) NOT NULL DEFAULT 'ATTACK' CHECK (stage IN ('ATTACK', 'DEFENSE')),
    ADD COLUMN attack_skipped BOOLEAN    NOT NULL DEFAULT false;

-- 공격·방어 검증. 방어 검증의 보안·기능 테스트 결과는 #11에서 컬럼을 더한다
CREATE TABLE verifications (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id  BIGINT       NOT NULL REFERENCES lab_sessions (id) ON DELETE CASCADE,
    type        VARCHAR(7)   NOT NULL CHECK (type IN ('ATTACK', 'DEFENSE')),
    status      VARCHAR(7)   NOT NULL CHECK (status IN ('RUNNING', 'PASS', 'FAIL', 'ERROR')),
    message     VARCHAR(500),
    duration_ms BIGINT,
    started_at  TIMESTAMPTZ  NOT NULL,
    finished_at TIMESTAMPTZ
);

-- 세션마다 실행 중 검증은 하나. 동시에 두 요청이 와도 하나만 들어간다
CREATE UNIQUE INDEX verifications_one_running_uk ON verifications (session_id) WHERE status = 'RUNNING';

CREATE INDEX verifications_session_ix ON verifications (session_id, started_at DESC);

-- 공개한 힌트와 공개 시각. 내용은 challenge_hints에 있다
CREATE TABLE lab_session_hints (
    session_id  BIGINT      NOT NULL REFERENCES lab_sessions (id) ON DELETE CASCADE,
    stage       VARCHAR(7)  NOT NULL CHECK (stage IN ('ATTACK', 'DEFENSE')),
    position    SMALLINT    NOT NULL CHECK (position BETWEEN 1 AND 3),
    revealed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (session_id, stage, position)
);
