-- 문제 (ADR 012). 등록 방식과 나머지 컬럼은 #7에서 정한다
CREATE TABLE challenges (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slug       VARCHAR(50)  NOT NULL CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    title      VARCHAR(100) NOT NULL,
    image      VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT challenges_slug_uk UNIQUE (slug)
);
