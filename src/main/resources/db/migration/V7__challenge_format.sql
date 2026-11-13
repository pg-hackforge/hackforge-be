-- Challenge 형식(ADR 014). 값은 Challenge 저장소의 register.sh가 meta/에서 읽어 넣는다
-- 이미 있는 행(개발 seed)이 있어도 적용되도록 기본값으로 채운 뒤 기본값을 없앤다. 새 행은 모든 값을 넣어야 한다
ALTER TABLE challenges
    ADD COLUMN description          TEXT        NOT NULL DEFAULT '',
    ADD COLUMN solution             TEXT        NOT NULL DEFAULT '',
    ADD COLUMN attack_check         TEXT        NOT NULL DEFAULT 'exit 1',
    ADD COLUMN exploit              TEXT        NOT NULL DEFAULT 'exit 1',
    ADD COLUMN functional_test      TEXT        NOT NULL DEFAULT 'exit 1',
    ADD COLUMN max_defense_attempts SMALLINT    NOT NULL DEFAULT 1 CHECK (max_defense_attempts > 0),
    ADD COLUMN updated_at           TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE challenges
    ALTER COLUMN description DROP DEFAULT,
    ALTER COLUMN solution DROP DEFAULT,
    ALTER COLUMN attack_check DROP DEFAULT,
    ALTER COLUMN exploit DROP DEFAULT,
    ALTER COLUMN functional_test DROP DEFAULT,
    ALTER COLUMN max_defense_attempts DROP DEFAULT;

-- 공격 힌트 3개(방향, 위치, 공격 방법), 방어 힌트 3개(방향, 위치, 고치는 방법)
CREATE TABLE challenge_hints (
    challenge_id BIGINT     NOT NULL REFERENCES challenges (id) ON DELETE CASCADE,
    stage        VARCHAR(7) NOT NULL CHECK (stage IN ('ATTACK', 'DEFENSE')),
    position     SMALLINT   NOT NULL CHECK (position BETWEEN 1 AND 3),
    content      TEXT       NOT NULL,
    PRIMARY KEY (challenge_id, stage, position)
);
