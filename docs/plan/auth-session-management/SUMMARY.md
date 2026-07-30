# 세션 관리 — 로그아웃·재발급·내 정보

## 뭘 하나
로그아웃이 실제로 세션을 끝내게 만든다. `users`에 세대 번호 칸을 두고 JWT에 같이 담아, 로그아웃하면 번호를 올려 이전 토큰을 전부 거부한다.
같은 PR에서 앱 전용 재발급(`/api/auth/reissue`)과 `/api/me`도 낸다. 셋 다 세대 번호를 공유한다.

## 왜
지금 앱 사용자의 로그아웃은 아무 일도 하지 않는다 — 토스 연결 끊기만 하고 `tossUserKey`가 없으면 그대로 반환한다. 발급된 access 토큰은 1시간 동안 계속 통한다.

## 핵심 결정
- **세대 번호 방식** — 정수 칸 하나로 로그아웃·유출 대응을 다 덮는다. refresh 세션 저장은 기각(회전 로직 부담)
- **`gen` claim 없으면 0으로 해석** — 배포 순간 로그인 중인 사용자가 전부 튕기는 걸 막는다
- **`JwtProvider`는 DB를 안 본다** — 순수 코덱으로 남겨야 슬라이스 테스트의 `@MockBean JwtProvider` 관례가 유지된다. 대조는 `JwtAuthFilter`가 포트로 한다
- **구 발급/해석 메서드는 삭제** — 남기면 세대 번호 없는 토큰을 실수로 발급한다. 컴파일러가 호출부를 짚게 한다
- **토스는 범위 밖** — `/api/auth/toss/logout`은 세션 종료가 아니라 연결 끊기이고 미니앱은 별개 계정이다. 손대지 않는다. `TossLoginService`는 발급 시그니처 대응만
- **Redis 캐싱도 범위 밖** — PK 단건 조회다. 측정 후 별건

## 건드는 곳
`V34__add_token_generation_to_users.sql` · `User` · `JwtProvider` · `JwtAuthFilter` · `SecurityConfig` · `TokenGenerationPort`(신규) · `SessionUseCase`/`SessionService`(신규) · `SessionController`/`MeController`(신규) · `OAuthLoginService` · `TossLoginService` · 슬라이스 테스트 5개

## 리스크
- 인증된 요청마다 DB 조회 1회 증가 (PK 단건)
- `SecurityConfig` 의존성 추가로 슬라이스 테스트 5개가 깨진다 — 같은 커밋에서 처리
- Flyway를 끄고 손으로 짠 IT 스키마 3개가 `users`를 따로 정의한다 — V34를 거기에도 반영해야 한다(안 하면 BusAlert IT 16건이 깨진다)

## 검증법
`./gradlew test` — **706건 전부 통과, 실패 0** (`GRADLE_EXIT=0`).
핵심은 `TokenGenerationIT` 10건: 실제 DB로 로그인 → 로그아웃 → **같은 access 401** → 재로그인 → 200 을 돈다.
