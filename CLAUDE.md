# hackforge-be

Hackforge 백엔드. Java 25, Spring Boot 4.1.1, Gradle 9.8.1(wrapper), 패키지 `com.popogonry.hackforge`.

## 먼저 읽을 것

- 무엇을 왜 만드는지: `../hackforge-docs/product.md`, 결정은 `../hackforge-docs/decisions/`
- 작업 중인 기능: `../hackforge-docs/specs/<기능>/tasks.md`
- API 계약: `openapi.yaml`. API를 바꾸면 구현보다 이 파일을 먼저 고친다. 기존 API는 프론트가 옮겨갈 때까지 지우지 않는다.

## 명령 (Windows는 `gradlew.bat`)

- 빌드 + 테스트: `./gradlew build` (CI와 같은 명령)
- 테스트만: `./gradlew test`
- 실행: `./gradlew bootRun` → http://127.0.0.1:8080

## 작업 규칙

- `tasks.md`의 작업을 한 번에 하나씩 구현하고, 테스트를 같이 쓴다.
- 의존성은 `implementation` 등 현재 구성을 쓴다. Boot BOM이 관리하는 라이브러리는 버전을 생략한다.
- 스펙과 코드가 어긋나면 코드를 맞추지 말고 먼저 묻는다. 문서를 고치는 것이 먼저다.

## 커밋 금지

- kubeconfig(`~/.kube/*.yaml`), 인증서, 키, 토큰. 설정 파일에는 경로만 적는다.

## Git

- GitHub Flow를 쓴다. `main`에서 짧은 브랜치(`feat/...`, `fix/...`)를 따고 PR로 머지한다. `main`에 직접 push하지 않는다.
- PR 하나에는 `tasks.md` 작업 1~3개만 담는다. 본문에 이슈와 스펙 링크를 건다.
- 커밋 접두어: `feat:` `fix:` `docs:` `chore:`
- 라벨: `feature` `bug` `be` `fe`
