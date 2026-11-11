-- 개발 전용 seed. bootRun에서만 적용된다(build.gradle bootRun의 spring.flyway.locations).
-- 이미지는 hackforge-docs infra/lab-test-challenge에서 빌드해 개발 VM에 import한다(#6 작업 5)
INSERT INTO challenges (slug, title, image)
VALUES ('test-web', '테스트 문제 (nginx + PostgreSQL)', 'hackforge/lab-test-challenge:0.1.0')
ON CONFLICT (slug) DO UPDATE SET title = EXCLUDED.title, image = EXCLUDED.image;
