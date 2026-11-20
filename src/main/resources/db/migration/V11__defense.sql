-- 방어: 웹 IDE, 재배포, 초기화 (ADR 024)

-- 문제가 정한 수정 불가 파일(/app 기준 glob, 빈칸으로 나눔). 비어 있으면 백엔드 기본값을 쓴다
ALTER TABLE challenges ADD COLUMN readonly_paths VARCHAR(1000);

-- 재배포와 서비스 초기화. 빌드 로그는 끝 64KB만 둔다
CREATE TABLE redeploys (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id  BIGINT      NOT NULL REFERENCES lab_sessions (id) ON DELETE CASCADE,
    kind        VARCHAR(8)  NOT NULL CHECK (kind IN ('REDEPLOY', 'RESET')),
    status      VARCHAR(7)  NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'FAIL', 'ERROR')),
    message     VARCHAR(500),
    log         TEXT,
    duration_ms BIGINT,
    started_at  TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ
);

-- 세션마다 실행 중 재배포·초기화는 하나. 검증과 함께 막는 것은 앱이 세션 행을 잠그고 확인한다
CREATE UNIQUE INDEX redeploys_one_running_uk ON redeploys (session_id) WHERE status = 'RUNNING';

CREATE INDEX redeploys_session_ix ON redeploys (session_id, started_at DESC);
