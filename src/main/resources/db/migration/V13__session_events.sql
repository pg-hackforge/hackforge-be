-- 관리와 기록 (ADR 026)

-- 관리자 강제 종료
ALTER TABLE lab_sessions DROP CONSTRAINT lab_sessions_end_reason_check;
ALTER TABLE lab_sessions ADD CONSTRAINT lab_sessions_end_reason_check
    CHECK (end_reason IN ('USER', 'IDLE', 'POD_LOST', 'ADMIN'));

-- 세션 상태 기록. trigger가 넣고 앱은 읽기만 한다
CREATE TABLE session_events (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id    BIGINT      NOT NULL REFERENCES lab_sessions (id) ON DELETE CASCADE,
    from_status   VARCHAR(12),
    to_status     VARCHAR(12) NOT NULL,
    actor         VARCHAR(7)  NOT NULL CHECK (actor IN ('LEARNER', 'ADMIN', 'SYSTEM')),
    -- 요청한 사용자. 사용자가 지워져도 기록은 남도록 FK를 두지 않는다
    actor_user_id BIGINT,
    created_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX session_events_session_ix ON session_events (session_id, id);

-- 상태가 바뀔 때마다(생성 포함) 남긴다. 주체는 요청을 처리하는 transaction이 set_config로 적고(transaction 범위),
-- 없으면 SYSTEM(reconciler 등). 시각은 앱이 쓰는 status_changed_at
CREATE FUNCTION record_session_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' OR NEW.status IS DISTINCT FROM OLD.status THEN
        INSERT INTO session_events (session_id, from_status, to_status, actor, actor_user_id, created_at)
        VALUES (NEW.id,
                CASE WHEN TG_OP = 'UPDATE' THEN OLD.status END,
                NEW.status,
                COALESCE(NULLIF(current_setting('hackforge.actor', true), ''), 'SYSTEM'),
                NULLIF(current_setting('hackforge.actor_user_id', true), '')::BIGINT,
                NEW.status_changed_at);
    END IF;
    RETURN NULL;
END
$$;

CREATE TRIGGER lab_sessions_record_event
    AFTER INSERT OR UPDATE OF status ON lab_sessions
    FOR EACH ROW EXECUTE FUNCTION record_session_event();
