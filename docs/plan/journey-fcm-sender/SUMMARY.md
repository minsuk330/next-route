# FCM 발송기 (Phase 5 ③, L)

> codex 리뷰 반영 완료(2026-08-10). 쟁점 판단은 [PLAN.md](./PLAN.md) §12, 막는 문제 9개는 §13.

## 뭘 하나

②가 만든 `NotificationSender` 포트에 진짜 FCM 구현을 끼운다. **가장 최근에 등록한 기기 한 대**에
보내고, provider 가 죽었다고 답한 token 만 조건부로 끈다. 마이그레이션 없음.
**운영 발송은 이 PR 에서 켜지 않는다** — ⑤·⑥ 뒤에 켠다.

## 왜

지금은 큐에 쌓고 꺼내기까지 다 돌지만 sender 가 아무것도 안 보낸다. 이 PR 이 그 자리를 채운다.

## 핵심 결정

- **채널 라우터를 안 만든다.** 여정 알림은 `PUSH` 하나(`TECHNICAL_DECISIONS §2` LOCKED).
  PHASE-5 의 "토스 신분증이면 토스 메신저" 문장은 이 PR 이 정정한다
- **기기 한 대에만 보낸다**(`last_seen_at` 최신). 다중 기기 동시 발송은 요구사항이 아니고,
  그것을 하려면 기기별 결과를 행 하나로 접는 규칙(부분 실패 판정·순서 보존·대량 비활성화 방어)이
  전부 따라온다. 고른 기기가 죽어 있으면 그 한 건은 실패하되 그 행이 꺼져 다음부터는 다른 기기로 간다
- **모르는 실패 코드는 일시 실패.** 영구로 단정하면 알림이 조용히 사라진다
- **token 을 끄는 사유는 `UNREGISTERED` 하나뿐.** `INVALID_ARGUMENT`·`SENDER_ID_MISMATCH` 는
  payload·자격증명 문제라 끄면 설정 실수 한 번이 주소록을 지운다
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
- 설정 실수가 token 주소록을 손상시킬 수 있다 → 비활성화 사유를 `UNREGISTERED` 로 축소
- **기기 한 대만 쓰므로, 고른 기기가 죽어 있으면 다른 기기가 살아 있어도 그 알림 한 건은 유실된다.**
  다음 알림부터는 자연 복구(죽은 행이 꺼짐). 여러 대 동시 발송이 필요해지면 그때 fan-out 을 되살린다

## 검증법

`./gradlew test` 전체 + 버스 경로 회귀. 핵심 5개: ①발송 대상이 `last_seen_at` 최신 하나
②`UNREGISTERED` 만 꺼지고 `INVALID_ARGUMENT`·`SENDER_ID_MISMATCH` 는 안 꺼짐 ③`lastSeenAt` 이
바뀐 token 은 안 꺼짐 ④APNs 헤더가 epoch 초, Android 는 duration ⑤`sender=fcm` + 자격증명 없음
→ 부팅 실패.

## 구현하며 달라진 것 (2026-08-10)

- **다중 기기 fan-out 을 걷어냈다.** 처음에는 살아 있는 token 전부에 multicast 로 보냈고, 그
  때문에 500개 chunk·결과 순서 재조립·기기별 결과를 행 하나로 접는 4단 규칙·대량 비활성화 상한이
  전부 필요했다. "알림은 한 대만"으로 정하니 그 넷이 통째로 사라졌다(main ~150줄·test ~290줄).
  대신 **선택 규칙**(`last_seen_at` 최신 하나)이 저장소로 들어왔다 — PLAN §1·§2.2·§2.3 과
  §4.1·§4.3 은 이 결정으로 대체된다
- **`FirebaseConfig` 위치** — 플랜은 `infrastructure/config/` 였으나 이 저장소에는 그 패키지가
  없다. 설정 클래스는 전부 `common/config/` 에 있어 그쪽 관례를 따랐다
- **firebase-admin 9.10.0 은 등록 token 발송을 deprecated 로 표시한다**(FID 권장). 옮기려면 앱이
  FID 를 올려 줘야 해 서버만으로는 못 한다 — 동작에는 영향이 없어 `@SuppressWarnings` 와 이유
  주석으로 두고 후속 과제로 남긴다
- **`compose.yaml` 의 worker** — YAML 병합(`<<`)은 목록을 이어 붙이지 않고 덮는다. worker 에
  `env_file`·`volumes` 를 더하면 공용 앵커의 항목이 사라지므로 그 목록을 다시 적었다
- **지표는 둘만 남았다** — `...push.token.deactivated`, `...push.no.active.token{channel}`.
  `failed{kind}` 에 섞으면 한 행이 두 번 세어지고 정상 기기 교체가 실패율을 부풀린다
