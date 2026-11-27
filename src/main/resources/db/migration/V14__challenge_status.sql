-- 문제 상태(hackforge-docs ADR 031 v1)와 분류 추가(ADR 032).
-- 예정(PLANNED) 문제는 목록 정보만 있고 실습을 시작할 수 없다. 공개(PUBLISHED)면 실습에 필요한 칸을 모두 채워야 한다
ALTER TABLE challenges
    ADD COLUMN status       VARCHAR(10) NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('PLANNED', 'PUBLISHED')),
    -- 처음 공개된 시각. 새 문제 표시와 새로 추가된 순의 기준. 다시 등록해도 바꾸지 않는다
    ADD COLUMN published_at TIMESTAMPTZ DEFAULT now();

UPDATE challenges SET published_at = created_at;

ALTER TABLE challenges
    ALTER COLUMN image DROP NOT NULL,
    ALTER COLUMN description DROP NOT NULL,
    ALTER COLUMN solution DROP NOT NULL,
    ALTER COLUMN attack_check DROP NOT NULL,
    ALTER COLUMN exploit DROP NOT NULL,
    ALTER COLUMN functional_test DROP NOT NULL,
    ALTER COLUMN max_defense_attempts DROP NOT NULL,
    ADD CONSTRAINT challenges_published_complete CHECK (status <> 'PUBLISHED' OR (
        image IS NOT NULL AND description IS NOT NULL AND solution IS NOT NULL AND attack_check IS NOT NULL
        AND exploit IS NOT NULL AND functional_test IS NOT NULL AND max_defense_attempts IS NOT NULL
        AND published_at IS NOT NULL));

-- 분류 17개. 보이는 순서는 비슷한 주제끼리(ADR 032). display_order가 UNIQUE라 먼저 겹치지 않는 값으로 옮긴다
UPDATE challenge_categories SET display_order = display_order + 100;

INSERT INTO challenge_categories (key, name, display_order)
VALUES ('nosql-injection', 'NoSQL Injection', 2),
       ('ssti', 'SSTI', 4),
       ('business-logic', '비즈니스 로직', 7),
       ('prototype-pollution', 'Prototype Pollution', 15),
       ('misconfiguration', '보안 설정 오류', 16);

UPDATE challenge_categories c
SET display_order = o.display_order
FROM (VALUES ('sqli', 1), ('xss', 3), ('access-control', 5), ('auth-session', 6), ('command-injection', 8),
             ('path-traversal', 9), ('file-upload', 10), ('ssrf', 11), ('csrf', 12), ('xxe', 13),
             ('deserialization', 14), ('info-disclosure', 17)) AS o (key, display_order)
WHERE c.key = o.key;

-- 예정 문제(hackforge-docs roadmap.md "문제 목록"). 예정 문제끼리는 최근 등록 먼저 보이므로 목록 순서의 반대로 넣는다.
-- 같은 slug가 이미 있으면(개발 DB에 먼저 등록한 경우) 건드리지 않는다
INSERT INTO challenges (slug, title, category_key, difficulty, estimated_minutes, summary, status, published_at)
VALUES ('deser-cookie', '직렬화된 쿠키로 코드 실행', 'deserialization', 'ADVANCED', 90,
        '로그인 쿠키에 든 직렬화 데이터를 조작해 서버에서 명령을 실행하고, 안전한 형식으로 바꿔요', 'PLANNED', NULL),
       ('ssrf-redirect', '리다이렉트로 내부 주소 필터 우회', 'ssrf', 'ADVANCED', 90,
        '외부 주소가 내부 주소로 리다이렉트하게 해 필터를 넘고, 실제로 접속하는 주소를 검사하게 고쳐요', 'PLANNED', NULL),
       ('sqli-blind', '참·거짓 응답 차이로 비밀번호 알아내기', 'sqli', 'ADVANCED', 90,
        '오류도 결과도 안 보이는 화면에서 응답 차이로 비밀번호를 한 글자씩 알아내고, 쿼리를 고쳐요', 'PLANNED', NULL),
       ('xxe-import', 'XML 가져오기로 서버 파일 읽기', 'xxe', 'INTERMEDIATE', 60,
        '가져오기 기능에 외부 엔티티를 넣은 XML을 올려 서버 파일을 읽고, XML 파서 설정을 고쳐요', 'PLANNED', NULL),
       ('ssrf-preview', '링크 미리보기로 내부 관리 API 호출', 'ssrf', 'INTERMEDIATE', 60,
        '링크 미리보기에 내부 주소를 넣어 밖에서 닿지 않는 관리 API를 부르고, 요청 주소를 제한해요', 'PLANNED', NULL),
       ('upload-ext', '확장자 검사 우회', 'file-upload', 'INTERMEDIATE', 60,
        '허술한 확장자 검사를 우회해 실행되는 파일을 올리고, 업로드 검사와 저장 방식을 고쳐요', 'PLANNED', NULL),
       ('auth-jwt', '약한 서명 키로 JWT 위조', 'auth-session', 'INTERMEDIATE', 60,
        '추측하기 쉬운 서명 키를 찾아 관리자 JWT를 만들고, 키와 토큰 검증을 고쳐요', 'PLANNED', NULL),
       ('sqli-search', '검색창 UNION으로 다른 테이블 읽기', 'sqli', 'INTERMEDIATE', 60,
        '상품 검색에 UNION을 넣어 회원 테이블을 읽고, 검색 쿼리를 안전하게 고쳐요', 'PLANNED', NULL),
       ('csrf-transfer', '포인트 송금 요청 위조', 'csrf', 'BEGINNER', 40,
        '피해자가 링크를 열기만 해도 포인트가 송금되는 페이지를 만들고, 요청 위조를 막아요', 'PLANNED', NULL),
       ('upload-profile', '프로필 사진 업로드로 웹셸 올리기', 'file-upload', 'BEGINNER', 40,
        '프로필 사진 대신 웹셸을 올려 서버 명령을 실행하고, 업로드를 이미지만 받게 고쳐요', 'PLANNED', NULL),
       ('access-role', '회원 정보 수정 요청에 role을 끼워 관리자 되기', 'access-control', 'BEGINNER', 40,
        '내 정보 수정 요청에 role 값을 더해 관리자가 되고, 바꿀 수 있는 항목을 제한해요', 'PLANNED', NULL),
       ('auth-reset', '비밀번호 재설정 토큰 추측', 'auth-session', 'BEGINNER', 40,
        '예측할 수 있는 재설정 토큰을 맞혀 남의 비밀번호를 바꾸고, 토큰 발급을 고쳐요', 'PLANNED', NULL),
       ('xss-board', '게시판 저장형 XSS로 관리자 세션 탈취', 'xss', 'BEGINNER', 40,
        '게시글에 스크립트를 심어 관리자가 글을 열 때 세션을 빼내고, 출력을 안전하게 고쳐요', 'PLANNED', NULL),
       ('xss-search', '검색어가 그대로 출력되는 반사형 XSS', 'xss', 'INTRO', 30,
        '검색어가 화면에 그대로 나오는 곳에서 스크립트를 실행하고, 출력 escape를 넣어요', 'PLANNED', NULL),
       ('path-download', '파일 다운로드 경로 조작', 'path-traversal', 'INTRO', 30,
        '다운로드 경로에 ../를 넣어 서버의 다른 파일을 받고, 경로 검사를 고쳐요', 'PLANNED', NULL),
       ('info-backup', '남겨진 백업 파일과 디버그 페이지', 'info-disclosure', 'INTRO', 30,
        '실수로 남은 백업 파일과 디버그 페이지에서 비밀 값을 찾고, 노출되지 않게 정리해요', 'PLANNED', NULL),
       ('cmdi-ping', '네트워크 진단으로 명령 실행', 'command-injection', 'BEGINNER', 40,
        'ping 진단 기능에 명령을 이어 붙여 서버 명령을 실행하고, 셸 없이 실행하게 고쳐요', 'PLANNED', NULL),
       ('idor-memo', '남의 비공개 메모 열람', 'access-control', 'BEGINNER', 40,
        '메모 번호를 바꿔 남의 비공개 메모를 읽고, 메모 주인을 확인하게 고쳐요', 'PLANNED', NULL)
ON CONFLICT (slug) DO NOTHING;
