# Commit Convention

## 포맷

```
<type>(<scope>): <subject>

<body>

<footer>
```

- 모든 줄은 100자 이하
- subject만 필수, body와 footer는 선택

---

## Type

| type | 설명 |
|------|------|
| feat | 새로운 기능 |
| fix | 버그 수정 |
| docs | 문서 변경 |
| style | 코드 포맷 변경 (동작에 영향 없는 변경) |
| refactor | 리팩토링 (기능 변경 없음) |
| test | 테스트 추가/수정 |
| chore | 빌드, 설정, 의존성 등 유지보수 |

---

## Scope

커밋이 영향을 미치는 범위를 명시한다.

| scope | 대상 |
|-------|------|
| bus | 버스 도메인 전반 (entity, repository, service, dto) |
| subway | 지하철 도메인 전반 |
| segment | 구간소요시간 도메인 |
| api | API 클라이언트/어댑터 (infrastructure/adapter/out) |
| scheduler | 스케줄러 (infrastructure/adapter/in) |
| config | 설정 (application.yaml, ApiClientConfig 등) |
| docker | Dockerfile, compose.yaml |
| env | 환경변수, env 파일 |

- 여러 scope에 걸치면 가장 핵심적인 하나를 선택하거나 생략한다.

---

## Subject

- 명령형 현재형 사용: "add", "fix", "change" (~~added~~, ~~fixed~~, ~~changed~~ 아님)
- 첫 글자 소문자
- 끝에 마침표 없음

---

## Body

- 변경 이유와 이전 동작과의 차이를 설명한다.
- 명령형 현재형 사용

---

## Footer

- **Breaking Changes**: `BREAKING CHANGE:` 접두사와 함께 변경 내용, 사유, 마이그레이션 방법 기술
- **이슈 참조**: `Closes #123` 형식

---

## 예시

```
feat(bus): add bus arrival raw data collection

3분 주기로 경유노선 전체 정류소별 도착예정정보를 수집하여
bus_arrival_raw 테이블에 적재한다.
```

```
fix(api): handle timeout on getBusRouteList call
```

```
chore(env): remove hardcoded DB credentials from application.yaml
```
