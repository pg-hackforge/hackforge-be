-- 개발 전용 seed. bootRun에서만 적용된다(build.gradle bootRun의 spring.flyway.locations).
-- 이미지는 hackforge-docs infra/lab-test-challenge에서 빌드해 개발 VM에 import한다(#6 작업 5)
-- 클러스터 확인용 문제라 검증 스크립트와 힌트는 자리 채움 값이다. 실제 문제는 Challenge 저장소의 register.sh로 넣는다(ADR 014)
INSERT INTO challenges (slug, title, image, description, solution, attack_check, exploit, functional_test,
                        max_defense_attempts, updated_at)
VALUES ('test-web', '테스트 문제 (nginx + PostgreSQL)', 'hackforge/lab-test-challenge:0.1.0',
        '실습 환경 확인용 테스트 문제. 공격할 취약점이 없다', '없음', 'exit 1', 'exit 1', 'exit 0', 1, now())
ON CONFLICT (slug) DO UPDATE SET title = EXCLUDED.title, image = EXCLUDED.image, description = EXCLUDED.description,
    solution = EXCLUDED.solution, attack_check = EXCLUDED.attack_check, exploit = EXCLUDED.exploit,
    functional_test = EXCLUDED.functional_test, max_defense_attempts = EXCLUDED.max_defense_attempts,
    updated_at = EXCLUDED.updated_at;

DELETE FROM challenge_hints WHERE challenge_id = (SELECT id FROM challenges WHERE slug = 'test-web');
INSERT INTO challenge_hints (challenge_id, stage, position, content)
SELECT c.id, h.stage, h.position, h.content
FROM challenges c,
     (VALUES ('ATTACK', 1, '테스트 문제라 공격할 곳이 없다'), ('ATTACK', 2, '-'), ('ATTACK', 3, '-'),
             ('DEFENSE', 1, '테스트 문제라 고칠 곳이 없다'), ('DEFENSE', 2, '-'), ('DEFENSE', 3, '-'))
         AS h (stage, position, content)
WHERE c.slug = 'test-web';
