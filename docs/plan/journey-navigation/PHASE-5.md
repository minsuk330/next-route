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

**진행 상황** — ①②③④⑤⑥ 구현 완료
(플랜: [journey-push-token](../journey-push-token/SUMMARY.md),
[journey-notification-outbox](../journey-notification-outbox/SUMMARY.md),
[journey-fcm-sender](../journey-fcm-sender/SUMMARY.md),
[journey-checkpoint-policy](../journey-checkpoint-policy/SUMMARY.md),
[journey-notification-guards](../journey-notification-guards/SUMMARY.md),
[journey-push-payload-deeplink](../journey-push-payload-deeplink/SUMMARY.md)).
③ 까지 머지돼도 **운영 발송은 꺼져 있다** — 활성화 게이트는 ③ 절 표를 본다.

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
- 사용자당 여러 기기를 허용한다. 표에는 `enabled = true` 인 행이 여럿 남는다 —
  **③이 그중 가장 최근 등록 하나를 발송 대상으로 고른다**(초안의 "전부에게 보낸다"는 ③에서 바뀌었다)

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

`NotificationSender` 포트의 Firebase Admin SDK 구현. **구현 완료**
(플랜: [journey-fcm-sender](../journey-fcm-sender/SUMMARY.md),
[journey-checkpoint-policy](../journey-checkpoint-policy/SUMMARY.md),
[journey-notification-guards](../journey-notification-guards/SUMMARY.md),
[journey-push-payload-deeplink](../journey-push-payload-deeplink/SUMMARY.md)).

- **채널 라우터는 만들지 않는다.** 여정 알림은 `PUSH` 하나다(`TECHNICAL_DECISIONS §2` LOCKED —
  "1차에서는 `FcmNotificationSender` 하나만 구현한다"). 이 문서에 있던 "토스 신분증이면 토스
  메신저" 문장은 그 결정과 어긋나 ③이 지웠다. `NotificationChannelResolver` 포트와 `TOSS` 채널
  값은 남아 있으므로, 토스 채널이 실제로 필요해지면 그때 라우터를 만든다
- 기존 `TossMessengerPort` 와 버스 도착 알림 경로는 한 줄도 건드리지 않았다
- **알림은 기기 한 대에만 간다.** 표는 사용자당 여러 token 을 허용하지만(①), 발송 대상은
  `last_seen_at` 이 가장 큰 살아 있는 token 하나다. 여러 대에 보내면 알림 한 건의 결말을 기기별
  결과에서 접어야 해 규칙이 크게 늘어난다(부분 실패 판정·결과 순서 보존·대량 비활성화 방어).
  고른 기기가 죽어 있으면 그 알림 한 건은 실패하지만, `UNREGISTERED` 응답으로 그 행이 꺼지므로
  다음 알림부터는 그다음 기기가 대상이 된다
- **token 을 비활성화하는 사유는 `UNREGISTERED` 하나뿐이다.** `INVALID_ARGUMENT` 는 payload·TTL·
  크기 문제로도 나고 `SENDER_ID_MISMATCH` 는 서버 자격증명이 틀린 것이라, 둘로 끄면 설정 실수
  한 번이 주소록을 지운다
- **모르는 실패 코드는 일시 실패로 본다.** 영구로 단정하면 알림이 조용히 사라진다
- FCM TTL 은 outbox 의 `expires_at` 에서 만든다. Android 는 남은 시간, APNs 는 epoch 초로
  **단위가 달라** 값 하나에서 둘을 파생한다
- scope 별 collapse key(체크포인트 제외)로 같은 환승의 옛 알림을 새 알림이 덮게 한다
- 자격증명은 **worker 컨테이너에만** 읽기 전용으로 마운트한다. 초기화 실패는 부팅을 막는다
- L 인 이유 — 외부 SDK 도입과 부분 실패 분류·token 수명 관리가 함께 온다

**⚠ 이 PR 은 운영 발송을 켜지 않는다.** `sender` 는 `noop`, `relay.enabled` 는 `false` 그대로다.

| 시점 | 스위치 |
|---|---|
| ③ 머지 | `sender=noop`·`relay.enabled=false` 유지. 행은 쌓이고 아무것도 안 나간다 |
| ⑤·⑥ 머지 뒤 | 아래 §1.1 절차로 켠다 |
| 되돌리기 | `relay.enabled=false` |

### 1.1 운영 발송 켜기 (⑥ 뒤의 별도 작업)

코드 변경과 섞지 않는다. 되돌릴 것이 스위치 하나로 남아야 한다.

1. worker 컨테이너에 서비스 계정 JSON 을 **읽기 전용**으로 마운트(`compose.yaml`, ③에서 준비됨)
2. worker 프로필만 `journey.notification.sender=fcm` → **부팅 확인**. 자격증명이 없거나 틀리면
   부팅이 막힌다(조용히 아무것도 안 보내는 상태가 가장 나쁘다)
3. `journey.notification.relay.enabled=true`
4. 되돌리기는 `relay.enabled=false` 하나. 행은 다시 쌓이기만 한다

⑤(발송 직전 lease·version 재검증)와 ⑥(payload 계약·최종 문구) 없이 켜면 낡은 알림이 나가고
임시 문구가 운영에 굳는다.

### ④ `journey-checkpoint-policy`

**언제 보낼지**를 정한다. **구현 완료**
(플랜: [journey-checkpoint-policy](../journey-checkpoint-policy/SUMMARY.md),
[journey-notification-guards](../journey-notification-guards/SUMMARY.md),
[journey-push-payload-deeplink](../journey-push-payload-deeplink/SUMMARY.md)).

- **체크포인트는 셋에서 멈춘다** — `TRANSFER_RISK_AT_RISK`·`TRANSFER_RISK_MISSED`(②가 만든 N6·N7)
  에 `TRANSFER_TARGET_APPROACHING`(N2)을 더한다. N4·N5·N8 은 사건이 아니라 알림을 열었을 때 볼
  **내용**이라 ⑥ payload·앱 화면이 소유한다
- N1(정류장 접근)·N3(하차 임박)은 **어휘에 아예 없다.** 모바일 local notification 소유이므로 서버가
  보낼 수단 자체를 두지 않는다 — "하차 유형 FCM enqueue 0" 이 검사가 아니라 타입으로 지켜진다
- 상태가 **나빠질 때만** 보내는 규칙과 떨림 방지는 ②가 이미 구현했다. ④는 그 위에 접근 알림을
  얹는다
- **배타성의 축은 상태가 아니라 단계다.** 정류장에 서 있으면 여유는 `다음 차 도착 - 지금` 이라,
  차가 창(기본 120초) 안에 오면 상태는 거의 항상 `AT_RISK` 다. 그 자리에서 `AT_RISK` 는 위험이
  아니라 탈 준비 신호이고 "지금 이동하라"는 문구는 틀린 지시이므로, **대기 단계에서는 위험 알림이
  `AT_RISK` 를 양보하고 접근 알림이 그 자리를 맡는다.** `MISSED` 는 양보하지 않는다
- **취소 규칙은 비대칭이다.** 상태가 좋아지는 회복은 위험 알림만 지우고 접근 알림을 보호한다.
  나빠지는 승격은 아직 발송되지 않은 접근 알림을 취소한다 — 놓친 마당에 "곧 온다"가 뒤늦게
  도착하면 거짓 안심이다
- 접근 알림은 마커 없이 `deliveryKey` 로만 중복을 막고, 만료는 **다음 차 도착 시각 그대로**다
  (바닥을 씌우면 차가 떠난 뒤에 "곧 온다"가 나간다). 그래서 **환승당 평생 1회**다
- 선행 수정 하나가 갈라져 나갔다 — 정류장에 서 있는 사용자에게 하차 버퍼와 환승 도보를 다시
  더해 준비 시각이 3분 넘게 미래로 밀리던 문제(PR #127). 그것 없이는 접근 알림이 발화할 수 없었다
- L 인 이유 — 규칙이 평가기 결과와 결합하고, "보내지 않을 이유"가 보낼 이유보다 많다

### ⑤ `journey-notification-guards`

발송 직전에 다시 확인한다. **계산 시점과 발송 시점 사이에 세상이 바뀐다.** **구현 완료**
(플랜: [journey-notification-guards](../journey-notification-guards/SUMMARY.md),
[journey-push-payload-deeplink](../journey-push-payload-deeplink/SUMMARY.md)).

- 재확인은 `JourneyNotificationOutboxStateService#loadTask` 한 곳이 소유한다. 순서는 만료 →
  여정 `ACTIVE` → `routeRevision` → **lease** → **`commandVersion`** → **접근 알림의 뜻**
- **`commandVersion` 만 엄격 비교한다.** `evaluationVersion` 은 ETA 숫자가 몇 초 흔들리기만 해도
  오르므로(대기 단계 10초 주기) 취소 근거로 쓰지 않는다 — 쓰면 정상 알림이 발송 직전에 대거 죽는다.
  조용히 사라지는 알림이 한 번 더 가는 알림보다 나쁘다
- **접근 알림만 최신 환승 상태를 확인한다.** 놓쳤으면(`transfer-missed`) 거짓말이고, 이미 탔으면
  (`already-boarded`) 늦은 알림이다. ④가 `PENDING` 만 취소할 수 있어 넘긴 경쟁 창을 여기서 닫는다
- **위험 알림에는 상태 확인을 걸지 않는다.** `MISSED` 알림을 환승 상태로 거르면 그 알림이 자기
  자신을 지운다. 그래서 환승 조회도 접근 알림일 때만 한다
- **재탐색 후 이전 revision 알림 발송 0** 이 통과 기준이다. 사용자가 다른 경로를 받았는데 옛 경로
  기준 알림이 가면 그 알림은 틀린 것이 아니라 해로운 것이다
- lease 가 만료된 뒤에는 새 enqueue 도 발송도 없다
- 만료(`expires_at`)가 지난 알림은 보내지 않고 `CANCELED`
- 취소 사유는 이름을 따로 둔다 — `expired`·`journey-ended`·`route-changed`·`superseded`·
  `lease-expired`·`command-changed`·`transfer-missed`·`already-boarded`. 뭉뚱그리면 재검증이
  과한지 모자란지 운영에서 못 가른다

### ⑥ `journey-push-payload-deeplink`

payload 계약과 앱 진입. **구현 완료**
(플랜: [journey-push-payload-deeplink](../journey-push-payload-deeplink/SUMMARY.md)).

- payload 에는 **식별자·세 version·문구 키·deep link 만** 담는다. 상태 값을 담지 않는다 — 앱은
  push 를 열면 REST 로 최신 snapshot 을 다시 조회한다(SC-3 2항)
- **deep link 는 서버가 완성한 문자열**이다:
  `nextroute://journey/{journeyId}?alertId={notificationId}&scope=TRANSFER&scopeId={n}`.
  앱이 식별자로 조립하게 하면 라우팅 규칙이 두 벌이 된다. https 유니버설 링크는 도메인에
  `apple-app-site-association`·`assetlinks.json` 배포가 선행돼야 해 지금은 못 쓴다
- **`notification` + `data` 를 함께 보낸다.** 포그라운드 억제는 앱이 한다 — iOS 무음(data-only)
  push 는 OS 가 미루거나 버려서 알림이 아예 안 뜨는 사례가 생긴다(G8 은 클라이언트 소유)
- **문구는 서버가 갖고 `messageKey` 를 동봉**한다. 앱이 원하면 키로 자기 문구를 쓰고, 안 쓰면 서버
  문구가 그대로 보인다
- **알림 조회 API 는 만들지 않았다.** 기존 snapshot 의 `legs`·`eta.activeTransfer` 로 대상을 그릴 수
  있다. 알림 이력 화면이 생기면 그때 만든다
- 확정 문구 — 접근 "갈아탈 차가 곧 도착해요 / 탑승 준비를 하세요.", 위험 "환승 시간이 빠듯해요 /
  서두르면 다음 차를 탈 수 있어요.", 놓침 "환승을 놓쳤어요 / 앱에서 새 안내를 확인하세요."
  (`AT_RISK` 본문의 "지금 이동하면"은 ④가 대기 중 `AT_RISK` 를 접근 알림에 넘겨 어색해져 바꿨다)

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
