# 여정 푸시 토큰 등록 (Phase 5 ①)

## 뭘 하나

기기 push token 을 받아 두는 테이블 `user_push_token`(V39) 하나와 등록·해제 API 두 개
(`PUT`/`DELETE /api/me/push-token`)를 만든다. 알림 발송 자체는 이 PR 에 없다 — 보낼 곳만 만든다.

## 왜

Phase 4 로 서버는 환승 위험을 계산해 DB 에 적어 두지만 사용자에게 밀어 보낼 주소가 없다.
뒤따르는 outbox(②)·FCM sender(③)가 둘 다 "이 사용자에게 보낼 token 목록"을 여기서 읽는다.

## 핵심 결정

- **token 유일성은 부분 unique 인덱스**(`WHERE deleted_at IS NULL`) — 통 unique 면 한 번 해제한
  token 을 그 기기가 영영 재등록 못 한다. FCM 이 같은 token 을 다시 내주기 때문
- **등록은 `ON CONFLICT DO UPDATE` 한 문장** — 동시 등록 race 를 SQL 안에서 흡수. find-then-save 기각
  (unique 위반 시 트랜잭션이 이미 rollback-only)
- **같은 token 재등록 시 소유자를 제자리 교체** — 기기 물려주기·계정 전환이 실제로 있고, 안 떼어
  내면 한 기기에 두 사람 알림이 간다
- **DELETE 는 soft delete, `enabled=false` 가 아님** — `enabled` 는 provider 가 `UNREGISTERED` 라
  답했을 때 우리가 끄는 자리(PR ③). 섞으면 기기 물려주기가 막힌다
- **DELETE 가 JSON 본문으로 token 을 받는다** — 쿼리 파라미터는 access log 에 token 평문이 남는다

## 건드는 곳

신규: `V39__create_user_push_token.sql`, `domain/push/*`, `application/push/*`,
`infrastructure/adapter/in/api/push/*`, `push-token-it-schema.sql`
수정: `SecurityConfig`, `.gitignore`, `JourneySchemaIT`

## 리스크

- `SecurityConfig` 의 `/api/me` 는 **정확 경로 매처**라 지금 상태로는 `/api/me/push-token` 이
  익명에 열린다. `/api/me/**` 추가가 필수이고, 익명 401 테스트가 그 가드
- `.gitignore` 마이그레이션 whitelist 를 빠뜨리면 로컬·CI 는 통과하고 prod 부팅만 죽는다
- DELETE 본문을 중간 프록시가 벗길 수 있다 — 모바일 배포 전 VPS 리버스 프록시 확인 필요

## 검증법

`./gradlew test` 전부 통과(Docker 필요). 특히 ①익명 요청 401 ②다른 사용자가 같은 token 재등록
시 이전 소유자에게서 떨어짐 ③2스레드 동시 등록에도 살아 있는 행 1개 ④400 응답 본문에 token 문자열
없음 — 이 네 가지가 이 PR 의 핵심 계약.
