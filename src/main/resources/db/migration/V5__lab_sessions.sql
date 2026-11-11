-- 실습 세션 (ADR 012). id로 namespace 이름(lab-<id>)을 만든다
CREATE TABLE lab_sessions (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id           BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    challenge_id      BIGINT       NOT NULL REFERENCES challenges (id) ON DELETE RESTRICT,
    image             VARCHAR(255) NOT NULL,
    status            VARCHAR(15)  NOT NULL
        CHECK (status IN ('CREATING', 'INITIALIZING', 'READY', 'TERMINATING', 'DELETED', 'FAILED')),
    end_reason        VARCHAR(15)  CHECK (end_reason IN ('USER', 'IDLE', 'POD_LOST')),
    start_attempts    SMALLINT     NOT NULL DEFAULT 0,
    status_changed_at TIMESTAMPTZ  NOT NULL,
    last_activity_at  TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL,
    ready_at          TIMESTAMPTZ,
    ended_at          TIMESTAMPTZ
);

-- 학습자당 진행 중 세션 1개. 끝난 상태(DELETED, FAILED)는 제외
CREATE UNIQUE INDEX lab_sessions_user_active_uk ON lab_sessions (user_id)
    WHERE status NOT IN ('DELETED', 'FAILED');

-- reconciler는 진행 중 세션만 조회한다
CREATE INDEX lab_sessions_active_ix ON lab_sessions (status)
    WHERE status NOT IN ('DELETED', 'FAILED');
