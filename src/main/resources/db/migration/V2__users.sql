-- 계정 (ADR 011). 이메일은 소문자로 저장한다
CREATE TABLE users (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email             VARCHAR(254) NOT NULL,
    password_hash     VARCHAR(100) NOT NULL,
    name              VARCHAR(50)  NOT NULL,
    nickname          VARCHAR(20)  NOT NULL,
    affiliation       VARCHAR(100) NOT NULL,
    signup_note       VARCHAR(200),
    role              VARCHAR(10)  NOT NULL CHECK (role IN ('LEARNER', 'AUTHOR', 'ADMIN')),
    status            VARCHAR(10)  NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    privacy_agreed_at TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT users_email_uk UNIQUE (email),
    CONSTRAINT users_nickname_uk UNIQUE (nickname)
);
