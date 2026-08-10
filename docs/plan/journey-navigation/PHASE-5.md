# Phase 5 — PR 분해 인덱스

- 상위 계획: [PLAN.md](./PLAN.md) §Phase 5
- 요구사항: [REQUIREMENTS.md](./REQUIREMENTS.md) §4.5 (N1~N8)
- 확정 기술 결정: [TECHNICAL_DECISIONS.md](./TECHNICAL_DECISIONS.md)
- 범위 변경 이력: [SCOPE-CHANGES.md](./SCOPE-CHANGES.md) (SC-1·SC-3)
- 앞 단계: [PHASE-4.md](./PHASE-4.md)
- 작성일: 2026-08-06

이 문서는 **어떤 PR 을 어떤 순서로 낼지 정리한 목차**다. 구현 상세는 각 PR 의 `PLAN.md` 에 쓴다.

## 0. Phase 5 가 해결하는 것

Phase 4 로 서버는 **사용자가 화면을 안 봐도 환승 위험을 안다**. 그런데 알릴 방법이 없다.
"곧 놓칠 것 같다"를 계산해 DB 에 적어 두기만 하고, 사용자는 앱을 열어야 그것을 본다.

Phase 5 는 그 계산 결과를 **사용자 화면까지 밀어 보낸다**. 세 조각이다.

1. **보낼 곳** — 기기 push token 을 받아 두고 관리한다
2. **보내는 길** — 지금은 토스 메신저 하나뿐이다. 네이티브 앱용 FCM 을 새로 만든다
3. **언제 보낼지** — 값이 흔들릴 때마다 보내면 알림이 소음이 된다. 상태가 실제로 나빠질 때만 한 번

### 지금 있는 것과 없는 것

| | 상태 |
|---|---|
| 발송 채널 | **토스 메신저뿐**(`TossMessengerPort`). FCM 은 코드에 없다 |
| 발송 outbox | 버스 도착 알림용 `bus_alert_notification`(V28)만 있다. 여정용은 없다 |
| outbox 패턴 | claim·재시도·백오프·DEAD 가 이미 검증돼 있다 — **그대로 따른다** |
| 여정 알림 테이블 | 없다. Phase 4 ②가 "종료 시 pending 알림 취소"를 SQL 한 줄 자리로 비워 뒀다 |
| 평가기 | 만들어져 있으나 **기본 꺼짐**. 알림도 그 스위치를 따른다 |

### 채널이 둘이 되는 것이 이 Phase 의 축이다

D12 로 확정된 신원 구조 때문이다 — 토스는 미니앱 전용이고, 네이티브 iOS·Android 앱은 자체
OAuth(APPLE·KAKAO)로 가입한다. 따라서 **같은 알림을 사용자에 따라 다른 길로 보내야 한다.**

REQUIREMENTS §4.5 가 이미 그것을 전제로 키를 나눠 뒀다.

- `logicalCheckpointKey = journeyId:routeRevision:scopeType:scopeId:checkpoint` — **사용자에게 같은
  내용을 두 번 보이지 않기 위한** 키. 채널과 무관하다
- `deliveryKey = logicalCheckpointKey:channel` — 채널별 발송 멱등 키

## 1. PR 목록

| 순서 | 슬러그 | 티어 | 마이그레이션 | 선행 |
|---|---|---|---|---|
| ① | `journey-push-token` | **M** | V39 `user_push_token` | — |
| ② | `journey-notification-outbox` | **L** | V40 `journey_notification` | ① |
| ③ | `journey-fcm-sender` | **L** | 없음 | ① |
| ④ | `journey-checkpoint-policy` | **L** | 없음 | ② |
| ⑤ | `journey-notification-guards` | **M** | 없음 | ②③④ |
| ⑥ | `journey-push-payload-deeplink` | **M** | 없음 | ②③ |
| ⑦ | `journey-alert-telemetry` | **M** | 없음 | ⑥ |

①이 끝나면 ②와 ③은 서로 독립이라 동시에 진행할 수 있다. 마이그레이션 번호는 **V39 부터**다
(Phase 4 는 마이그레이션이 없었다).

**진행 상황** — ①② 구현 완료
(플랜: [journey-push-token](../journey-push-token/SUMMARY.md),
[journey-notification-outbox](../journey-notification-outbox/SUMMARY.md)).

후속 과제 둘이 남았다.

- ① — 계정 삭제(탈퇴) 경로가 생기면 그 사용자의 `user_push_token` 행을 전부 soft delete 해야
  한다(PLAN §7 개인정보). 지금은 탈퇴 서비스가 코드베이스에 없어 죽은 메서드를 만들지 않고
  `UserPushTokenRepository` Javadoc 에만 남겼다
- ② — **`journey_notification` 보관(retention)**. 이 표의 행은 지금 <b>절대 지우지 않는다</b>.
  `delivery_key` 의 unique 가 부분 인덱스가 아니라서, 지우는 순간 키가 열려 같은 알림이 다시
  나가기 때문이다. 지금 규모에서는 문제없지만 정리가 필요해지면 <b>흔적(tombstone) 표를 먼저
  설계</b>해야 한다. Phase 6 이후 과제

### ① `journey-push-token`

기기 push token 을 받아 두는 테이블과 API.

- `user_push_token(user_id, token, platform, enabled, last_seen_at, ...)` 신설(V39).
  SC-1 로 기기 세션 개념을 지웠으므로 **기기 식별자를 저장하지 않는다** — 사용자와 token 만 묶는다
- `PUT /api/me/push-token`(등록·교체), `DELETE /api/me/push-token`(해제).
  경로는 SCOPE-CHANGES 에서 `/api/me/device/push-token` → `/api/me/push-token` 으로 확정됐다
- 같은 token 이 다른 사용자에게 재등록되면 앞선 소유자에게서 떼어 낸다. 기기를 물려주거나 계정을
  바꿔 로그인하는 경우가 실제로 있다
- 사용자당 여러 기기를 허용한다. 발송 대상은 `enabled = true` 인 token 전부

### ② `journey-notification-outbox`

여정 알림의 발송 기록과 상태 기계. **Phase 5 의 중심이다.**

- `journey_notification` 신설(V40). `bus_alert_notification`(V28)의 모양을 따르되 여정 식별자를 갖는다:
  `journey_id·route_revision·scope_type·scope_id·checkpoint·channel·logical_checkpoint_key·delivery_key`
- 상태: `PENDING → PROCESSING → SENT / FAILED / DEAD / CANCELED`. 전이 규칙과 claim 쿼리는
  `BusAlertOutboxRelayService`·`BusAlertNotificationRepository` 를 그대로 따른다 — **여기서 새로
  설계하지 않는다**
- **enqueue 는 계산과 분리한다.** 평가기는 "이 체크포인트가 발생했다"만 남기고, 발송은 relay 가 한다.
  Phase 4 에서 외부 호출을 트랜잭션 밖으로 뺀 것과 같은 이유다
- **여정이 끝나면 pending 을 취소한다.** Phase 4 ②의 만료 SQL 에 한 줄을 더한다(그 자리를 비워 뒀다)
- L 인 이유 — 상태 기계·동시성·취소 경로가 한꺼번에 얽히고, 채널이 둘이라 멱등 키가 두 겹이다

**구현하며 드러난 것 3가지**(자세한 내용은 ②의 SUMMARY).

1. `JourneyLeaseExpiryService.sweep()` 이 같은 클래스의 `expireBatch()` 를 직접 불러
   `@Transactional` 이 <b>안 걸리고 있었다</b>(기존 버그). 만료 SQL 이 한 문장이라 우연히
   안전했을 뿐이고, 취소 쿼리를 더하면 원자성이 깨진다. ②가 먼저 고쳤다
2. 체크포인트 값이 <b>상태를 품어야</b> 한다(`TRANSFER_RISK_AT_RISK`/`_MISSED`). 하나로 두면
   승격이 같은 `deliveryKey` 를 만들어 `ON CONFLICT` 에 삼켜진다
3. 만료 시각에 <b>바닥(180초)</b>이 필요하다. `MISSED` 는 다음 차 도착이 이미 과거라 그대로
   쓰면 삽입 순간 만료된다. 바닥은 재시도 예산(백오프 60초 × 3회)과 같아야 한다

### ③ `journey-fcm-sender`

`NotificationSender` 포트와 Firebase Admin SDK 기반 구현.

- 포트는 채널을 모른다. 부르는 쪽은 "이 사용자에게 이 알림을" 이라고만 말하고, **어느 길로 갈지는
  라우터가 정한다** — 토스 신분증(`provider='TOSS'`)이면 토스 메신저, 아니면 FCM
- 기존 `TossMessengerPort` 는 그대로 두고 새 포트 뒤로 감싼다. 버스 도착 알림 경로는 안 건드린다
- 영구 실패를 가른다: `UNREGISTERED`·`INVALID_ARGUMENT` 는 재시도하지 않고 token 을 비활성화한다.
  일시 실패는 백오프 후 재시도
- FCM TTL 은 outbox 의 `expires_at` 에서 만든다. 늦게 도착한 알림은 사용자를 엉뚱한 때에 움직인다
- scope 별 collapse key 로 같은 환승의 옛 알림을 새 알림이 덮게 한다
- **자격증명이 없어도 구현은 진행한다.** Firebase project·APNs 인증키 발급은 병행하고, 테스트는
  SDK 를 mock 으로 둔다(토스 로그인 때와 같은 방식)
- L 인 이유 — 외부 SDK 도입과 채널 라우팅 경계 신설이 함께 온다

### ④ `journey-checkpoint-policy`

**언제 보낼지**를 정한다. 서버가 소유하는 것은 N2·N4~N8 이다.

- N1(정류장 접근)·N3(하차 임박)은 **모바일 local notification 소유**다. 서버는 그 유형을 FCM 으로
  보내지 않는다 — 검증 항목에 "하차 유형 FCM enqueue 0" 이 있다
- 상태가 **나빠질 때만** 보낸다. `COMFORTABLE → CATCHABLE` 은 알리지 않고
  `CATCHABLE → AT_RISK`, `AT_RISK → MISSED` 를 알린다. ETA 가 몇 초 흔들리는 것은 화면만 갱신한다
- **떨림 방지(hysteresis)** — 경계에서 오가는 값 때문에 같은 알림이 반복되면 안 된다.
  `TransferRiskPolicy` 가 이미 판정에 hysteresis 를 갖고 있으므로 그 결과를 쓴다
- 중복 방지는 `logicalCheckpointKey` 로 한다. 같은 여정·같은 revision·같은 환승·같은 체크포인트는
  **한 번만** 사용자에게 보인다
- L 인 이유 — 규칙이 평가기 결과와 결합하고, "보내지 않을 이유"가 보낼 이유보다 많다

### ⑤ `journey-notification-guards`

발송 직전에 다시 확인한다. **계산 시점과 발송 시점 사이에 세상이 바뀐다.**

- 발송 직전 재검증: 여정이 아직 `ACTIVE` 인가, lease 가 살아 있나, `routeRevision` 이 그대로인가,
  `commandVersion`·`evaluationVersion` 이 어긋나지 않았나
- **재탐색 후 이전 revision 알림 발송 0** 이 통과 기준이다. 사용자가 다른 경로를 받았는데 옛 경로
  기준 알림이 가면 그 알림은 틀린 것이 아니라 해로운 것이다
- lease 가 만료된 뒤에는 새 enqueue 도 발송도 없다
- 만료(`expires_at`)가 지난 알림은 보내지 않고 `CANCELED`

### ⑥ `journey-push-payload-deeplink`

payload 계약과 앱 진입.

- payload 에는 **식별자·세 version·deep link 만** 담는다. 상태 값을 담지 않는다 — 앱은 push 를 열면
  REST 로 최신 snapshot 을 다시 조회한다(SC-3 2항)
- `journeyId + alertId` 로 조회하고, 알림 대상 leg·transfer 의 다음 행동을 표시한다
- 포그라운드에서는 OS 알림을 띄우지 않고 앱 내부 배너만 — payload 와 클라이언트 핸들러를 나눠
  그 구분이 가능하게 한다

### ⑦ `journey-alert-telemetry`

효과를 잰다. 안 재면 알림을 늘릴지 줄일지 판단할 근거가 없다.

- push 선택률, 알림 이후 GPS 단계 전이·재탐색 수락까지의 지연(alert-to-action)
- 채널별 발송·성공·영구 실패 수, token 비활성화 수
- SC-3 5항의 계측 항목과 맞춘다

## 2. 티어 판정 근거

CLAUDE.md 의 L 판별 기준 — ①고칠 코드의 모양이 미리 그려지지 않음 ②스키마·API·모듈 경계를 새로
만듦 ③PR 이 여러 개 필요 ④설계 대안을 두고 다툴 여지가 있음.

| 슬러그 | 티어 | 근거 |
|---|---|---|
| journey-push-token | M | 테이블 1개 + CRUD API. 경로와 필드가 SCOPE-CHANGES 에 확정 |
| journey-notification-outbox | **L** | 상태 기계·동시성·취소 경로가 얽히고 멱등 키가 두 겹(②④) |
| journey-fcm-sender | **L** | 외부 SDK 도입 + 채널 라우팅 경계 신설(②) |
| journey-checkpoint-policy | **L** | 규칙이 평가기와 결합하고 "안 보낼 조건"이 많다(①④) |
| journey-notification-guards | M | 재검증 조건이 이미 열거돼 있다. 붙일 자리도 하나 |
| journey-push-payload-deeplink | M | payload 계약이 SC-3 에 확정 |
| journey-alert-telemetry | M | 지표 추가. 기존 계측 관례를 따른다 |

## 3. Phase 5 공통 함정

- **평가기가 꺼져 있으면 알림도 없다.** 머지만으로는 아무 일도 일어나지 않는다. 전체 발송 활성화는
  Phase 4 안정화 이후다(PLAN §Phase 5)
- **사용자 표시 중복과 채널 발송 멱등은 다른 문제다.** `logicalCheckpointKey` 는 "두 번 보이지 않게",
  `deliveryKey` 는 "두 번 보내지 않게". 하나로 합치면 채널이 둘일 때 무너진다
- **하차·정류장 접근을 서버에서 보내지 마라.** N1·N3 은 모바일 소유다. 서버가 함께 보내면 사용자는
  같은 것을 두 번 본다
- **payload 에 상태를 담지 마라.** 사용자가 30분 뒤에 알림을 열면 그 값은 이미 틀렸다. 식별자만 담고
  앱이 다시 조회한다
- **claim 과 발송을 같은 트랜잭션에 넣지 마라.** Phase 4 에서 겪은 것과 같은 실수다. provider 응답을
  기다리는 동안 DB 커넥션을 붙잡는다
- **마이그레이션 번호는 V39 부터.** `.gitignore` 가 migration 을 파일별 whitelist 로 관리하므로 새
  SQL 마다 `!` 라인을 추가한다. `JourneySchemaIT` 의 `@Sql` 목록에도 넣는다
- **알림 발송 실패가 여정을 실패로 만들면 안 된다.** 알림은 부가 기능이고 여정은 본체다

## 4. Phase 5 완료 조건

- [ ] PR 을 ①②③④⑤⑥⑦ 순서대로 머지(②③ 은 병렬 가능)
- [ ] REQUIREMENTS 의 N2·N4~N8 이 서버에서 발송되고 N1·N3 은 발송되지 않음
- [ ] 같은 서버 체크포인트 중복 enqueue 0, 하차 유형 FCM enqueue 0
- [ ] 재탐색 후 이전 `routeRevision` 알림 발송 0
- [ ] lease 만료 이후 신규 enqueue·발송 0
- [ ] 일시 실패·영구 실패·token 무효·provider 지연을 가르는 통합 시험 통과
- [ ] 포그라운드에서 OS 알림 0회·앱 내부 배너 1회, 백그라운드·잠금화면에서 OS 알림과 deep link 동작
- [ ] 오래된 push 를 열어도 payload 상태를 적용하지 않고 최신 snapshot 으로 수렴
- [ ] 기존 버스 도착 알림·토스 발송 경로에 회귀 없음

## 5. Phase 6 이후로 넘긴 것

- 환승 시점 부분 재탐색 제안(`reroute_proposal`) — Phase 6
- 알림 채널 확장(SMS 등) — 지금은 토스·FCM 둘
- 사용자별 알림 설정 화면(끄기·시간대 제한) — 요구사항에 아직 없다
