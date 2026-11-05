# hackforge-be

Hackforge 백엔드. Java 25, Spring Boot 4.1.1, Gradle 9.8.1(wrapper), 패키지 `com.popogonry.hackforge`.

## 먼저 읽을 것

- 무엇을 왜 만드는지: `../hackforge-docs/product.md`, 결정은 `../hackforge-docs/decisions/`
- 작업 중인 기능: `../hackforge-docs/specs/<기능>/tasks.md`
- API spec: `openapi.yaml`. API를 바꿀 때는 구현보다 이 파일을 먼저 고치고, 기존 API는 프론트가 옮겨갈 때까지 지우지 않는다.

## 명령 (Windows는 `gradlew.bat`)

Docker Desktop이 켜져 있어야 한다. 테스트는 Testcontainers로, `bootRun`은 `compose.yaml`로 PostgreSQL을 띄운다.

- 빌드 + 테스트: `./gradlew build` (CI와 같은 명령)
- API spec lint: `npx --yes @redocly/cli@2.65.0 lint openapi.yaml` (CI도 같은 명령을 먼저 돌린다)
- 테스트만: `./gradlew test`
- 실행: `./gradlew bootRun` → http://127.0.0.1:8080 (dev DB 컨테이너도 자동으로 시작, 앱 종료 시 정지)
- dev DB 접속: `docker compose exec postgres psql -U hackforge`

## 작업 규칙

- `tasks.md` 작업을 한 번에 하나씩 구현하고 테스트도 같이 쓴다.
- DB schema는 `src/main/resources/db/migration/V<번호>__<이름>.sql`로만 바꾼다. 머지된 파일은 고치지 않고 새 파일을 추가한다(ADR 010).
- 의존성은 `implementation` 등 지금 쓰는 configuration을 따르고, Boot BOM이 관리하는 라이브러리는 버전을 생략한다.
- spec과 코드가 어긋나면 코드를 맞추지 말고 먼저 묻는다. 문서를 먼저 고친다.

## 커밋 금지

- kubeconfig(`~/.kube/*.yaml`), 인증서, 키, token. 설정 파일에는 경로만 적는다.

## Git

- GitHub Flow. `main`에서 짧은 브랜치(`feat/...`, `fix/...`)를 따서 PR로 머지하고, `main`에 직접 push하지 않는다.
- PR 하나에 `tasks.md` 작업 1~3개만 담는다. 본문은 PR 템플릿 섹션을 모두 채운다(근거에 이슈와 작업 번호).
- 커밋 접두어: `feat:` `fix:` `docs:` `chore:`
- 커밋 본문에는 diff로 알 수 없는 이유만 한두 줄 쓴다. 뻔한 변경은 제목 한 줄로 끝낸다.
- 라벨: `feature` `bug` `be` `fe` `infra`
- `suhodang/<이 저장소 이름>`은 `main`과 tag를 자동으로 덮어쓰는 read-only mirror다(`.github/workflows/mirror.yml`). 거기서 작업하지 않는다.
