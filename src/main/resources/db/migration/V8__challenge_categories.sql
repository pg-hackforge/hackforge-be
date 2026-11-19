-- 문제 정보: 분류, 난이도, 예상 시간, 요약(hackforge-docs ADR 020).
-- 분류는 이 테이블로 고정하고 문제가 참조한다. 분류를 더하거나 이름을 바꾸려면 새 migration을 쓴다.
CREATE TABLE challenge_categories (
    key           VARCHAR(30) PRIMARY KEY CHECK (key ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    name          VARCHAR(50) NOT NULL,
    display_order SMALLINT    NOT NULL,
    CONSTRAINT challenge_categories_display_order_uk UNIQUE (display_order)
);

INSERT INTO challenge_categories (key, name, display_order)
VALUES ('sqli', 'SQL Injection', 1),
       ('xss', 'XSS', 2),
       ('access-control', '접근 제어', 3),
       ('auth-session', '인증·세션', 4),
       ('command-injection', 'Command Injection', 5),
       ('path-traversal', 'Path Traversal', 6),
       ('file-upload', '파일 업로드', 7),
       ('ssrf', 'SSRF', 8),
       ('csrf', 'CSRF', 9),
       ('xxe', 'XXE', 10),
       ('deserialization', '역직렬화', 11),
       ('info-disclosure', '정보 노출', 12);

-- 이미 등록된 문제는 임시 값으로 채우고, Challenge 저장소의 register.sh로 다시 등록해 실제 값으로 바꾼다
ALTER TABLE challenges
    ADD COLUMN category_key      VARCHAR(30) NOT NULL DEFAULT 'sqli'
        REFERENCES challenge_categories (key) ON DELETE RESTRICT,
    ADD COLUMN difficulty        VARCHAR(12) NOT NULL DEFAULT 'INTRO'
        CHECK (difficulty IN ('INTRO', 'BEGINNER', 'INTERMEDIATE', 'ADVANCED')),
    ADD COLUMN estimated_minutes SMALLINT    NOT NULL DEFAULT 30 CHECK (estimated_minutes > 0),
    ADD COLUMN summary           VARCHAR(80) NOT NULL DEFAULT '';

UPDATE challenges SET summary = left(title, 80);

ALTER TABLE challenges
    ALTER COLUMN category_key DROP DEFAULT,
    ALTER COLUMN difficulty DROP DEFAULT,
    ALTER COLUMN estimated_minutes DROP DEFAULT,
    ALTER COLUMN summary DROP DEFAULT,
    ADD CONSTRAINT challenges_summary_not_empty CHECK (summary <> '');
