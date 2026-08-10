# FCM 발송기 (Phase 5 ③, L)

> codex 리뷰 반영 완료(2026-08-10). 쟁점 판단은 [PLAN.md](./PLAN.md) §12, 막는 문제 9개는 §13.

## 뭘 하나

②가 만든 `NotificationSender` 포트에 진짜 FCM 구현을 끼운다. 사용자의 살아 있는 push token
전부에 나눠 보내고, provider 가 죽었다고 답한 token 만 조건부로 끈다. 마이그레이션 없음.
**운영 발송은 이 PR 에서 켜지 않는다** — ⑤·⑥ 뒤에 켠다.

## 왜

지금은 큐에 쌓고 꺼내기까지 다 돌지만 sender 가 아무것도 안 보낸다. 이 PR 이 그 자리를 채운다.

## 핵심 결정

- **채널 라우터를 안 만든다.** 여정 알림은 `PUSH` 하나(`TECHNICAL_DECISIONS §2` LOCKED).
  PHASE-5 의 "토스 신분증이면 토스 메신저" 문장은 이 PR 이 정정한다
- **transient 가 성공보다 우선한다.** `1 성공 + 1 UNAVAILABLE` 을 `SENT` 로 끝내면 그 기기는
  영구 누락된다. 중복 재발송은 ② 계약이 이미 수용한 비용
- **token 을 끄는 사유는 `UNREGISTERED` 하나뿐.** `INVALID_ARGUMENT`·`SENDER_ID_MISMATCH` 는
  payload·자격증명 문제라 끄면 설정 실수 한 번이 주소록을 전멸시킨다. 상한 가드도 둔다
- **비활성화는 조건부 UPDATE.** 발송 중 재등록된 token 을 꺼 버리지 않게 관측한
  `userId`·`lastSeenAt` 을 조건에 넣는다
- **TTL 은 값 하나에서 두 단위로.** Android 는 남은 시간, APNs 는 epoch 초 — 같은 숫자를 넣으면
  iOS 알림이 즉시 만료된다. `data` 는 식별자 허용 목록만(상태값 금지)
- **시크릿은 worker 에만.** relay 가 worker 전용이고, API 에 서비스 계정을 주면 권한이 샌다

## 건드는 곳

신규: `FirebaseConfig`, `FirebaseMessagingClient`(포트), `FirebaseAdminMessagingClient`,
`FcmNotificationSender`, `FcmMessageFactory`, `PushTokenDeactivationService`
수정: `build.gradle`, `application.yaml`, `JourneyNotificationProperties`, **`NotificationSender`**,
**`NotificationSendCommand`**, `JourneyNotificationOutboxStateService`,
`JourneyNotificationMetrics`, **`UserPushTokenRepository`**, `UserPushToken`, `compose.yaml`,
`PHASE-5.md`

## 리스크

- 외부 자격증명이 처음 들어온다 — `secrets/` 읽기 전용 마운트, base64 환경변수는 기각
- 설정 실수가 token 주소록을 대량 손상시킬 수 있다 → 비활성화 사유 축소 + 상한 가드
- 결과 배열과 token 목록의 순서 대응이 어긋나면 엉뚱한 기기가 꺼진다 → 경계가 순서를 보장, 501개
  경계 테스트

## 검증법

`./gradlew test` 전체 + 버스 경로 회귀. 핵심 5개: ①`성공+UNAVAILABLE` → 재시도 ②`UNREGISTERED`
만 꺼지고 `INVALID_ARGUMENT` 는 안 꺼짐 ③`lastSeenAt` 이 바뀐 token 은 안 꺼짐 ④APNs 헤더가
epoch 초, Android 는 duration ⑤`sender=fcm` + 자격증명 없음 → 부팅 실패.

## 구현하며 달라진 것 (2026-08-10)

- **`FirebaseConfig` 위치** — 플랜은 `infrastructure/config/` 였으나 이 저장소에는 그 패키지가
  없다. 설정 클래스는 전부 `common/config/` 에 있어 그쪽 관례를 따랐다
- **firebase-admin 9.10.0 은 등록 token 발송을 deprecated 로 표시한다**(FID 권장). 옮기려면 앱이
  FID 를 올려 줘야 해 서버만으로는 못 한다 — 동작에는 영향이 없어 `@SuppressWarnings` 와 이유
  주석으로 두고 후속 과제로 남긴다
- **`compose.yaml` 의 worker** — YAML 병합(`<<`)은 목록을 이어 붙이지 않고 덮는다. worker 에
  `env_file`·`volumes` 를 더하면 공용 앵커의 항목이 사라지므로 그 목록을 다시 적었다
- **상한 가드의 자리** — 사용자당 token 수는 상한이 없어 `deactivate-max-per-send` 판단은
  `PushTokenDeactivationService` 가 소유한다. 발송기는 끌 목록을 통째로 넘기기만 한다
- **기기 단위 지표는 행 지표와 분리** — `journey.notification.push.tokens{outcome}`,
  `...push.token.deactivated`, `...push.no.active.token{channel}`. `failed{kind}` 에 섞으면 한
  행이 두 번 세어진다
