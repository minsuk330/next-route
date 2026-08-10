# journey-fcm-sender — 실행 플랜

- 티어: **L** (codex 논의 완료 → 새 세션에서 구현)
- 브랜치: `feat/journey-fcm-sender` (**② PR #123 머지 후** fresh `origin/main` 기준)
- 상위: [Phase 5 인덱스](../journey-navigation/PHASE-5.md) §1 ③
- 선행: ① `journey-push-token`(머지 #122, V39) · ② `journey-notification-outbox`(PR #123, V40)
- 마이그레이션: **없음**
- 근거: `TECHNICAL_DECISIONS.md` §2(LOCKED), `REQUIREMENTS.md` §4.5·§6, `PHASE-5.md` §1 ③,
  ② PLAN `§5.3 NotificationSender 계약`
- codex 설계 리뷰 반영: 2026-08-10 (§13 에 반영 내역)

> 이 문서는 컨텍스트 없는 세션이 읽고 바로 구현할 수 있도록 쓴다. 사람 검토용 요약은
> [SUMMARY.md](./SUMMARY.md).

---

## 0. 이 PR 이 하는 일

②가 만든 `NotificationSender` 포트에 **진짜 발송 구현**을 끼운다. no-op 을 FCM 으로 바꾸는 것이
전부지만, 그 안에 외부 SDK 도입·자격증명·부분 실패 분류·token 수명 관리가 함께 들어온다.

②가 끝난 시점의 상태는 이렇다: 위험을 감지하면 행이 쌓이고, relay 가 꺼내고, sender 가
**아무것도 안 하고** `SENT` 로 끝낸다. 이 PR 뒤에는 코드가 실제로 FCM 을 부를 수 있다.

**다만 이 PR 은 운영에서 발송을 켜지 않는다.** relay 스위치를 올리는 것은 ⑤(발송 직전 재검증)와
⑥(payload 계약)이 들어온 뒤다 — 이유는 §6.

### 용어

| 말 | 뜻 |
|---|---|
| FCM | Firebase Cloud Messaging. Android 는 직접, iOS 는 APNs 를 경유해 전달한다 |
| token | 기기 하나의 주소. `user_push_token.token`(V39). 사용자당 여러 개 |
| `UNREGISTERED` | 그 token 이 죽었다는 provider 의 답. 앱 삭제·재설치가 원인 |
| collapse key | 같은 열쇠를 가진 대기 중 메시지를 새 것이 덮게 하는 값 |
| TTL | 이 시간 안에 못 전달하면 버리라는 지시. outbox 의 `expires_at` 에서 만든다 |
| transient / permanent | 재시도하면 달라질 수 있는 실패 / 재시도해도 같은 실패 |

### 범위 밖 (다른 PR)

| 항목 | 담당 |
|---|---|
| 언제 보낼지(체크포인트 종류·N1·N3 금지 규칙) | ④ |
| 발송 직전 lease·`commandVersion`·`evaluationVersion` 재검증 | ⑤ |
| payload 계약·deep link·최종 문구 | ⑥ |
| alert-to-action 지표 | ⑦ |
| 운영 발송 활성화(`relay.enabled=true`) | ⑤·⑥ 이후 (§6) |

---

## 1. 확정 설계 결정 (재논의 금지 — codex 판단은 §12)

| 항목 | 결정 | 이유 |
|---|---|---|
| 채널 라우터 | **만들지 않는다.** 여정 알림은 `PUSH` 하나 | `TECHNICAL_DECISIONS §2`(LOCKED) — "1차에서는 `FcmNotificationSender` 하나만 구현한다". `PHASE-5.md:114` 의 "토스 신분증이면 토스 메신저" 문장은 LOCKED 와 어긋난다 → 이 PR 이 PHASE-5 를 고친다 |
| `channel-resolver` | `fixed`(`[PUSH]`) 그대로 | 바꿀 이유가 없다. `identity` 값은 토스 채널이 실제로 생기는 날까지 미사용 |
| SDK | Firebase Admin SDK(`com.google.firebase:firebase-admin`) | APNs 를 직접 부르지 않는다(LOCKED). iOS 전달은 Firebase 프로젝트에 APNs 키를 연결해 처리 |
| SDK 경계 | 우리 인터페이스 `FirebaseMessagingClient` 뒤에 SDK 를 숨긴다 | `FirebaseMessaging.getInstance()` 가 static 이라 그대로 쓰면 발송 분류를 단위 테스트로 못 잠근다 |
| 자격증명 없을 때 | `sender=fcm` 인데 초기화 실패면 **부팅을 막는다** | 조용히 안 보내는 상태가 가장 나쁘다. ②가 `relay + noop` 을 부팅에서 막은 것과 같은 이유 |
| 발송 단위 | 사용자의 살아 있는 token **전부에 한 번에**(multicast, 500개씩 나눠) | 행 하나 = "사용자 × 채널"(② §5.3). token 마다 행을 쪼개지 않는다 |
| **행 종결 우선순위** | **transient 가 하나라도 있으면 transient 가 이긴다**(성공이 섞여 있어도 재시도) | 성공 하나로 `SENT` 를 찍으면 일시 실패한 기기는 **영구 누락**된다. ② 계약이 "토큰별 일시 실패는 행 전체가 재시도"라고 못박았다 |
| **token 비활성화 대상** | **`UNREGISTERED` 하나뿐** | `INVALID_ARGUMENT` 는 payload·TTL·크기 문제로도 나고, `SENDER_ID_MISMATCH` 는 서버 자격증명이 틀린 것이다. 둘을 비활성화 사유로 쓰면 **설정 실수 한 번이 주소록을 통째로 지운다** |
| 대량 손상 방지 | 한 번의 발송에서 끄는 token 수에 상한(`deactivate-max-per-send`, 기본 50). 넘으면 **끄지 않고 경고만** | 위와 같은 사고를 상한이 한 번 더 막는다 |
| 비활성화 방식 | `id` + 관측한 `userId`·`lastSeenAt` 을 조건에 넣은 **조건부 UPDATE** | 발송 도중 같은 token 이 재등록되면 그 행은 새 소유자·새 `lastSeenAt` 이다. 조건 없이 끄면 **방금 등록한 기기를 꺼 버린다** |
| 살아 있는 token 0건 | 영구 실패(`FAILED`), 사유 `no-active-token`, **별도 지표** | 재시도해도 대개 같다. `CANCELED` 는 "보낼 필요가 없어진 것"(만료·여정 종료·supersede)이라 뜻이 다르다 |
| 전부 영구 실패 | 영구 실패(`FAILED`) | 사용자는 아무것도 못 받았다. `SENT` 로 찍으면 지표가 거짓말을 한다 |
| TTL | Android 는 **남은 시간**, APNs 는 **epoch 초**. 값은 `min(expiresAt, now + ttlMax)` 하나에서 파생 | 단위가 다르다. 남은 시간 `600` 을 `apns-expiration` 에 넣으면 1970년으로 읽혀 즉시 만료된다 |
| collapse key | scope 단위. Android `collapseKey`, APNs `apns-collapse-id` | 같은 환승의 옛 알림을 새 알림이 덮는다. APNs 제한 64바이트라 논리 키(최대 255자)를 못 쓴다 |
| 우선순위 | Android `high`, APNs `apns-priority: 10` | 환승 위험은 즉시 행동이 필요한 알림이다(LOCKED §2) |
| 메시지 내용 | `data` 는 **허용 목록만**, `notification` 은 체크포인트별 임시 문구 한 곳 | payload 에 상태를 담지 않는다(`REQUIREMENTS §4.5`). 앱은 최신 상태를 REST 로 다시 조회한다 |
| `dry-run` 옵션 | **넣지 않는다** | validate-only 성공이 일반 성공과 구분되지 않아 진짜 행을 `SENT` 로 소모한다. 점검이 필요하면 outbox 와 무관한 admin 경로를 따로 만든다(후속) |
| 타임아웃 | `fcm.timeout-ms` 는 **행 하나 전체의 예산**(chunk 마다가 아님). 기본 5,000ms | token 이 많으면 chunk 가 여러 번 돈다. chunk 별 예산이면 회수 주기(3분)를 넘길 수 있다 |
| 시크릿 위치 | **worker 서비스에만** 준다. 키 파일은 `secrets/` 아래 읽기 전용 마운트 | relay 는 `worker` 프로필 전용이다. API 컨테이너에 서비스 계정을 주면 권한이 새고, Firebase 초기화 실패가 API 부팅까지 막는다 |
| `user_push_token.environment` | 이 PR 에서 **쓰지 않는다.** "배포 하나 = Firebase 프로젝트 하나"를 불변식으로 적는다 | 값은 클라이언트가 주는 것이라 라우팅 근거가 못 된다. 환경을 나누려면 `FirebaseApp` 자체를 나눠야 한다 |
| 개인정보 | **token 을 로그·지표·예외 문구에 절대 싣지 않는다** | `REQUIREMENTS §6`, V39 헤더. 식별은 `notificationId` 로 한다 |

---

## 2. 반드시 지켜야 할 여덟 가지 (안 지키면 조용히 망가짐)

### 2.1 sender 는 트랜잭션 밖에서 돈다

relay 는 `@Transactional` 이 없다(② 설계). 그런데 이 PR 은 **token 을 끄는 쓰기**를 하게 된다.
그 쓰기는 반드시 자기 트랜잭션을 열어야 한다(`REQUIRES_NEW` 를 가진 별도 빈).

⚠ ②에서 확인된 사실: 이 코드베이스의 Spring Data 쿼리는 **트랜잭션 밖에서도 조용히 통과한다**
(암묵 read-only tx + PG 세션이 실제 read-only 가 아님). "안 붙여도 되던데요"가 성립하므로
붙였는지를 사람이 봐야 한다.

### 2.2 부분 실패를 뭉뚱그리면 안 되고, 비활성화는 최소로

`sendEachForMulticast` 는 token 별 결과를 배열로 준다. 그것을 `if (failureCount > 0) throw` 로
접으면 죽은 token 이 영영 안 꺼지고, 기기 하나 때문에 전체가 재시도된다.

| `MessagingErrorCode` | 뜻 | 행 판정 | token |
|---|---|---|---|
| `UNREGISTERED` | 앱 삭제·재설치로 죽은 token | permanent | **끈다** |
| `INVALID_ARGUMENT` | token 이 틀렸을 수도, **payload·TTL·크기·reserved key** 가 틀렸을 수도 | permanent | **살려 둔다** + 설정/조립 오류로 경고 |
| `SENDER_ID_MISMATCH` | 서버 자격증명과 token 의 Firebase 프로젝트가 다르다 | permanent | **살려 둔다** + 배포 사고 경고 |
| `THIRD_PARTY_AUTH_ERROR` | APNs 키·인증서 문제 | permanent | 살려 둔다 + 경고 |
| `UNAVAILABLE`·`INTERNAL` | provider 일시 장애 | transient | 살려 둔다 |
| `QUOTA_EXCEEDED` | 한도 초과 | transient | 살려 둔다 |
| 그 밖 | 모름 | **transient** | 살려 둔다 |

모르는 코드를 영구로 단정하지 않는다 — 단정하면 알림이 조용히 사라진다.

**대량 손상 방지**: 한 번의 발송에서 끌 token 이 `deactivate-max-per-send`(기본 50)를 넘으면
**하나도 끄지 않고** 경고만 남긴다. 그 규모의 `UNREGISTERED` 동시 발생은 token 문제가 아니라
우리 쪽 사고다.

### 2.3 행 종결 규칙 — transient 가 성공보다 우선한다

```
살아 있는 token 0건        → permanent("no-active-token")
transient ≥ 1              → transient   ← 성공이 섞여 있어도 재시도한다
transient 0, 성공 ≥ 1      → 정상 반환(relay 가 SENT)
transient 0, 성공 0        → permanent(FAILED)
```

두 번째 줄이 핵심이다. `1 성공 + 1 UNAVAILABLE` 을 `SENT` 로 끝내면 그 기기는 **영원히 못 받는다.**
재시도로 이미 성공한 기기에 중복이 가는 것은 ② 계약이 이미 수용한 비용이다(collapse key 가 줄인다).

### 2.4 collapse key 는 64바이트를 넘으면 안 된다

APNs `apns-collapse-id` 의 하드 제한. 넘기면 그 메시지가 **거부된다.**
논리 키(`7:1:TRANSFER:0:TRANSFER_RISK_AT_RISK`)는 최대 255자라 그대로 못 쓴다.

형식: `j{journeyId}r{routeRevision}{scope 첫 글자}{scopeId}` → 예 `j7r1T0`.
**체크포인트를 넣지 않는다** — 넣으면 `AT_RISK` 와 `MISSED` 가 서로 안 덮는다. 덮는 것이 목적이다.

### 2.5 token 문자열이 어디로도 새면 안 된다

로그·지표 태그·예외 메시지·트레이스 속성 전부. 실패는 **개수만** 센다.
⚠ SDK 예외 메시지에 token 이 들어 있을 수 있다. `getMessage()` 를 그대로 옮기지 말고 우리 문구로
감싼다.

### 2.6 payload 를 그대로 실으면 안 된다 (허용 목록만)

outbox `payload` 에는 지금 `status`·`transferSlackSeconds`·`nextBusArrivalAt` 같은 **상태값**이
들어 있다. 그것을 FCM `data` 로 평탄화하면 `REQUIREMENTS §4.5`("push 는 상태 저장소가 아니다")를
정면으로 어긴다 — 늦게 도착한 알림이 낡은 숫자를 사용자에게 보여 준다.

`data` 에 넣는 것은 **식별자뿐**이다:
`notificationId`, `journeyId`, `routeRevision`, `scopeType`, `scopeId`, `checkpoint`,
`commandVersion`, `evaluationVersion`.

- 값은 전부 문자열이어야 한다(FCM `data` 계약)
- FCM 예약 키(`from`, `google*`, `gcm*`, `notification`)와 겹치지 않게 한다
- payload 가 null 이거나 객체가 아니면 그냥 무시한다
- 전체 메시지 4KB 상한을 넘지 않는다(허용 목록만 실으면 자연히 지켜진다)

### 2.7 TTL 은 값 하나에서 두 단위로 파생한다

```
providerExpiresAt = min(command.expiresAt, now + ttlMax)
Android ttl       = Duration.between(now, providerExpiresAt)   // 남은 시간
APNs apns-expiration = providerExpiresAt.getEpochSecond()      // UTC epoch 초
```

같은 숫자를 양쪽에 넣으면 iOS 알림이 **즉시 만료**된다(600 → 1970-01-01 00:10:00).
테스트는 "TTL 값"이 아니라 Android duration 과 APNs 헤더 문자열을 각각 확인한다.

### 2.8 비활성화는 조건부 UPDATE 여야 한다

발송을 시작한 뒤 결과가 오기까지 사이에 같은 token 이 **재등록**될 수 있다. ① 의 upsert 는 그때
같은 행의 `user_id`·`enabled=true`·`last_seen_at` 을 갱신한다. `WHERE id IN (...)` 로 끄면
방금 등록한 기기(혹은 새 소유자)를 꺼 버린다.

조건에 관측값을 넣는다:

```sql
UPDATE user_push_token
   SET enabled = false, updated_at = :now
 WHERE id = :id
   AND user_id = :observedUserId
   AND last_seen_at = :observedLastSeenAt
   AND enabled = true
   AND deleted_at IS NULL
```

0행은 정상이다 — "더 새로운 등록이 이겼다"는 뜻이므로 로그만 남긴다.

---

## 3. 작업 순서 (체크박스, 의존성 명시)

### Step 1 — 의존성과 부팅
- [ ] `build.gradle` — `implementation 'com.google.firebase:firebase-admin:<최신 안정>'`
      (구현 시 버전 확인 후 고정)
- [ ] `infrastructure/config/FirebaseConfig.java` — `FirebaseApp` 초기화.
      `@ConditionalOnProperty(prefix="journey.notification", name="sender", havingValue="fcm")`
- [ ] `application/journey/config/JourneyNotificationProperties.java` — `Fcm` 중첩 클래스 +
      검증 3줄(§4.5). **단위를 맞춘 비교식**을 쓴다(§13 B9)
- [ ] 검증: `./gradlew compileJava` · 기본값(`sender=noop`)에서 컨텍스트가 그대로 뜬다
- [ ] 검증: `./gradlew test --tests '*JourneyNotificationPropertiesTest'`

### Step 2 — SDK 경계 (의존: Step 1)
- [ ] `application/journey/port/out/FirebaseMessagingClient.java` — 우리 인터페이스
- [ ] `infrastructure/adapter/out/notification/FirebaseAdminMessagingClient.java` —
      **500개 chunk + 결과 재조립 + 행 전체 deadline**
- [ ] 검증: `./gradlew test --tests '*FirebaseAdminMessagingClientTest'`(chunk 경계 500/501)

### Step 3 — 메시지 조립 (의존: Step 2)
- [ ] `infrastructure/adapter/out/notification/FcmMessageFactory.java` — TTL 두 단위·collapse key·
      우선순위·**허용 목록 data**·임시 문구가 존재하는 유일한 자리
- [ ] 검증: `./gradlew test --tests '*FcmMessageFactoryTest'`

### Step 4 — token 비활성화 (의존: 없음, Step 2·3 과 병렬 가능)
- [ ] `domain/push/repository/UserPushTokenRepository.java` — **조건부** `deactivate` 쿼리(§2.8)
- [ ] `application/push/service/PushTokenDeactivationService.java` — `REQUIRES_NEW` + 상한 가드
- [ ] `domain/push/entity/UserPushToken.java` — `environment` Javadoc 에 "라우팅에 쓰지 않는다"
      불변식 한 줄
- [ ] 검증: `./gradlew test --tests '*PushTokenDeactivation*' --tests '*UserPushToken*'`

### Step 5 — 발송기 (의존: Step 2·3·4)
- [ ] `infrastructure/adapter/out/notification/FcmNotificationSender.java`
      (`@ConditionalOnProperty(sender=fcm)`)
- [ ] `application/journey/port/out/NotificationSender.java` — **계약 문구 정정**:
      "토큰별 영구 실패 → 그 행은 SENT" 는 *일부* 실패 전제였다. §2.3 표로 바꾼다
- [ ] 검증: `./gradlew test --tests '*FcmNotificationSenderTest'`

### Step 6 — ② DTO 확장 (의존: 없음, Step 5 와 병렬 가능)
- [ ] `application/journey/dto/NotificationSendCommand.java` — `journeyId`·`routeRevision`·
      `scopeType`·`scopeId`·`commandVersion`·`evaluationVersion` 추가
- [ ] `application/journey/service/JourneyNotificationOutboxStateService.java` — `loadTask` 가
      새 필드를 채운다(전부 `JourneyNotification` 의 기존 컬럼이다)
- [ ] 검증: `./gradlew test --tests '*JourneyNotificationRelayIT'`

### Step 7 — 지표 (의존: Step 5)
- [ ] `application/journey/observability/JourneyNotificationMetrics.java` —
      `pushTokens{outcome}`, `tokenDeactivated`, `failed{kind=no-active-token}` 을 provider 실패와
      **분리해서** 센다
- [ ] 검증: `./gradlew test --tests '*JourneyNotification*'`

### Step 8 — 설정·시크릿 (의존: Step 1)
- [ ] `env/fcm.env.example` 신설
- [ ] `compose.yaml` — **worker 서비스에만** `env/fcm.env` 와 `secrets/` 읽기 전용 마운트를 붙인다
      (공용 앵커 `x-app-service` 에 넣지 않는다)
- [ ] `src/main/resources/application.yaml` — `journey.notification.fcm` 블록
- [ ] 검증: `sender=fcm` + 자격증명 없음 → **부팅 실패**를 테스트로 고정

### Step 9 — 전 구간 IT (의존: Step 5·6)
- [ ] `src/test/java/.../application/journey/notification/JourneyNotificationFcmRelayIT.java`
      (가짜 `FirebaseMessagingClient` 를 relay 에 꽂아 outbox → 발송 → token 비활성화까지)
- [ ] 검증: `./gradlew test --tests '*JourneyNotificationFcmRelayIT'`

### Step 10 — 마무리
- [ ] 전체 `./gradlew test`
- [ ] 버스 경로 회귀 `./gradlew test --tests 'watoo.grd.nextroute.application.notification.*'`
- [ ] `PHASE-5.md` ③ 진행 표시 + **채널 라우터 문장 정정** + **운영 활성화 게이트 명시**(§6)
- [ ] 커밋 + PR (플랜 파일 2개 포함)

---

## 4. 신규 파일 시그니처

### 4.1 SDK 경계

```java
// application/journey/port/out/FirebaseMessagingClient.java
/**
 * FCM 호출의 유일한 창구. SDK 타입을 이 인터페이스 밖으로 내보내지 않는다.
 *
 * <p>구현이 지는 책임 셋.
 * <ul>
 *   <li>token 을 500개씩 나눠 보낸다(FCM multicast 상한)</li>
 *   <li>결과를 <b>입력 순서 그대로</b> 다시 합친다 — 어긋나면 엉뚱한 token 이 꺼진다</li>
 *   <li>전체 호출이 {@code timeoutMs} 를 넘지 않게 한다. chunk 별 예산이 아니라 <b>행 전체</b>
 *       예산이다</li>
 * </ul>
 */
public interface FirebaseMessagingClient {

    /** @return 입력 token 과 같은 길이·같은 순서의 결과 */
    List<PushResult> send(PushMessage message, List<String> tokens);

    /** @param errorCode provider 가 준 코드. 성공이면 null */
    record PushResult(boolean success, String errorCode) {}
}
```

### 4.2 메시지 조립

```java
// infrastructure/adapter/out/notification/FcmMessageFactory.java
/**
 * outbox 행 하나를 FCM 메시지로 옮긴다. TTL·collapse key·우선순위·data 허용 목록·문구가
 * 존재하는 유일한 자리. PR6 이 payload 계약과 deep link 를 정할 때 여기만 고치면 된다.
 */
PushMessage from(NotificationSendCommand command, Instant now);
```

- `providerExpiresAt = min(command.expiresAt(), now + ttlMax)` → Android duration / APNs epoch(§2.7)
- `collapseKey = "j"+journeyId+"r"+routeRevision+scopeType.첫글자+scopeId` (≤64바이트, §2.4)
- `data` — §2.6 의 여덟 개. 값은 전부 문자열
- `notification` — 체크포인트별 임시 문구 한 벌(⑥ 이 교체할 자리)

### 4.3 발송기

```java
// infrastructure/adapter/out/notification/FcmNotificationSender.java
@Override
public void send(NotificationSendCommand command) {
    List<UserPushToken> tokens = tokenRepository
            .findByUserIdAndEnabledTrueAndDeletedAtIsNull(command.userId());
    if (tokens.isEmpty()) {
        throw new NotificationSendException("no-active-token", true);
    }

    List<PushResult> results = client.send(
            messageFactory.from(command, Instant.now(clock)),
            tokens.stream().map(UserPushToken::getToken).toList());

    // zip 하는 자리를 한 곳으로 모아 순서가 어긋날 여지를 없앤다.
    Outcome outcome = classify(tokens, results);

    // 끄는 것은 UNREGISTERED 뿐이고, 상한을 넘으면 아무것도 끄지 않는다(§2.2).
    deactivationService.deactivateIfSafe(outcome.unregistered());

    if (outcome.transientCount() > 0) {                     // 성공이 있어도 transient 가 이긴다
        throw new NotificationSendException(outcome.summary(), false);
    }
    if (outcome.successCount() > 0) {
        return;
    }
    throw new NotificationSendException(outcome.summary(), true);
}
```

`summary()` 는 **개수만** 담는다(§2.5).

### 4.4 token 비활성화

```java
// application/push/service/PushTokenDeactivationService.java
/**
 * provider 가 죽었다고 답한 token 을 끈다.
 *
 * <p>{@code REQUIRES_NEW} 다. relay 는 트랜잭션 밖에서 돌고 발송 결과 기록도 각자
 * {@code REQUIRES_NEW} 로 돈다(② 설계). 여기만 트랜잭션 없이 두면 조용히 안 꺼진다.
 *
 * <p>{@code deactivate-max-per-send} 를 넘으면 <b>하나도 끄지 않고</b> 경고만 남긴다. 그 규모의
 * 동시 UNREGISTERED 는 token 문제가 아니라 우리 쪽 사고다.
 *
 * @param observed 발송 시점에 본 (id, userId, lastSeenAt). 조건부 UPDATE 의 근거다(§2.8)
 * @return 실제로 꺼진 행 수. 0 은 정상 — 더 새로운 등록이 이겼다는 뜻이다
 */
@Transactional(propagation = Propagation.REQUIRES_NEW)
public int deactivateIfSafe(List<ObservedToken> observed);
```

### 4.5 설정

```yaml
journey:
  notification:
    sender: ${JOURNEY_NOTIFICATION_SENDER:noop}   # worker 에서만 fcm 으로 올린다
    fcm:
      credentials-path: ${FIREBASE_CREDENTIALS_PATH:}
      timeout-ms: 5000              # 행 하나 전체의 예산(chunk 마다가 아님)
      ttl-max-seconds: 1800         # outbox max-ttl 과 같은 값
      deactivate-max-per-send: 50   # 넘으면 하나도 끄지 않는다
```

`@PostConstruct validate()` 추가분:

| 규칙 | 구현 형태 | 이유 |
|---|---|---|
| `sender=fcm` 이면 `credentials-path` 가 비면 안 된다 | 문자열 검사 | 조용한 무발송 방지 |
| 타임아웃 × 2 가 회수 주기보다 짧아야 한다 | `Duration.ofMillis(timeoutMs).multipliedBy(2).compareTo(claimTimeout()) < 0` | **단위를 맞춘다.** `5000 * 2 < 3` 같은 비교는 정상 설정을 죽인다 |
| `ttl-max-seconds <= max-ttl-seconds` | 초 단위 비교 | provider 가 outbox 보다 오래 들고 있으면 만료 계약이 깨진다 |
| `deactivate-max-per-send > 0` | | |

---

## 5. 수정 파일

| 경로 | 변경 |
|---|---|
| `build.gradle` | firebase-admin 의존성 |
| `src/main/resources/application.yaml` | `journey.notification.fcm` 블록 |
| `application/journey/config/JourneyNotificationProperties.java` | `Fcm` 중첩 클래스 + 검증 4줄(§4.5) |
| **`application/journey/port/out/NotificationSender.java`** | 계약 문구 정정 — 종결 규칙을 §2.3 표로 |
| **`application/journey/dto/NotificationSendCommand.java`** | 식별자 6개 추가(§3 Step 6) |
| **`application/journey/service/JourneyNotificationOutboxStateService.java`** | `loadTask` 가 새 필드를 채운다 |
| `application/journey/observability/JourneyNotificationMetrics.java` | `pushTokens{outcome}`·`tokenDeactivated`·`no-active-token` 분리 |
| **`domain/push/repository/UserPushTokenRepository.java`** | 조건부 `deactivate` 쿼리(§2.8) |
| `domain/push/entity/UserPushToken.java` | `environment` 불변식 Javadoc 한 줄 |
| `compose.yaml` | **worker 서비스에만** `env/fcm.env` + `secrets/` 읽기 전용 마운트 |
| `docs/plan/journey-navigation/PHASE-5.md` | ③ 진행 표시 + 채널 라우터 문장 정정 + 활성화 게이트 |

**안 고치는 것**: `TossMessengerPort`·`TossMessengerAdapter`·버스 outbox 전부.
버스 도착 알림 경로는 이 PR 이 한 줄도 건드리지 않는다(Phase 5 완료 조건).

---

## 6. 자격증명·배포·활성화 게이트

- 서비스 계정 JSON 은 저장소에 넣지 않는다. `secrets/` 는 이미 `.gitignore` 대상이다
- 컨테이너에는 **읽기 전용 볼륨**으로 넣고 경로만 환경변수로 준다. base64 로 환경변수에 통째로
  넣는 안은 기각 — 프로세스 목록·크래시 덤프에 남는다
- **worker 서비스에만** 준다(§1). API 는 relay 를 돌리지 않는다
- `env/fcm.env.example` 로 필요한 키 목록만 저장소에 남긴다(`oauth.env.example` 관례)

### 활성화 게이트 — 이 PR 은 발송을 켜지 않는다

`loadTask` 는 지금 만료·`ACTIVE`·`routeRevision` 셋만 본다. lease 와 두 version 은 **⑤가 넣는다.**
payload 계약과 문구는 **⑥이 확정한다.** 그 둘 없이 relay 를 켜면 낡은 알림이 나가고 임시 문구가
운영에 굳는다.

| 시점 | 스위치 |
|---|---|
| 이 PR 머지 | `sender` 는 `noop` 유지, `relay.enabled=false` 유지 |
| ⑤·⑥ 머지 뒤 | 자격증명 배치 → worker 만 `sender=fcm` → 부팅 확인 → `relay.enabled=true` |
| 되돌리기 | `relay.enabled=false`. 행은 계속 쌓이고 아무것도 안 나간다 |

---

## 7. 위험과 그 처리

| 위험 | 처리 |
|---|---|
| 자격증명이 없거나 틀림 | 부팅에서 막는다(§1). 조용한 무발송이 가장 나쁘다 |
| 잘못된 자격증명으로 주소록 전멸 | `SENDER_ID_MISMATCH` 는 token 을 끄지 않는다 + 상한 가드(§2.2) |
| payload 오류로 주소록 전멸 | `INVALID_ARGUMENT` 도 token 을 끄지 않는다(§2.2) |
| 재등록 경쟁 | 조건부 UPDATE(§2.8). 0행은 정상 |
| 결과 배열 순서 어긋남 | 경계 인터페이스가 순서를 보장하고, 500/501 chunk 테스트가 고정 |
| SDK 가 느림 | 행 전체 deadline 5초. 회수 주기(3분)와의 관계를 부팅 검증이 지킨다 |
| token 유출 | 로그·지표·예외 문구 금지. 리뷰 체크 항목(§2.5) |
| 상태값이 push 로 샘 | `data` 허용 목록(§2.6) |
| iOS 즉시 만료 | TTL 단위 분리(§2.7) + 헤더 값을 직접 검증하는 테스트 |
| 중복 발송 | ② 의 "최소 한 번" 계약 그대로. collapse key 가 사용자 눈에 보이는 피해를 줄인다 |
| 정상 token churn 이 실패 지표를 부풀림 | `no-active-token`·`tokenDeactivated`·provider 실패를 각각 센다 |

---

## 8. `sender=noop` (현재 기본값)일 때

`FcmNotificationSender` 빈이 안 뜨고 `NoOpNotificationSender` 가 그대로 쓰인다.
`FirebaseApp` 초기화도 안 돈다 — 자격증명 없이 개발·테스트가 그대로 돌아간다.

⚠ ②가 `relay.enabled=true` + `sender=noop` 을 **프로필 예외 없이** 부팅에서 막는다
(`JourneyNotificationProperties.validate`). 전 구간 IT 는 relay 루프 빈을 띄우지 말고
`JourneyNotificationRelayService` 를 직접 부른다(②의 `JourneyNotificationRelayIT` 방식).

---

## 9. 테스트

### 준비물
- `src/test/java/.../notification/FakeFirebaseMessagingClient.java` — token 별 결과를 시나리오로
  심는다(성공·`UNREGISTERED`·`UNAVAILABLE`·`INVALID_ARGUMENT`·타임아웃). ②의
  `CountingNotificationSender` 와 같은 모양
- 단위 테스트는 SDK 를 아예 안 부른다. 경계 인터페이스만 있으면 된다

### 클래스별 검증 항목

| 클래스 | 검증 |
|---|---|
| `FcmNotificationSenderTest` (단위) | **`성공 + UNAVAILABLE` → transient**(성공이 있어도 재시도) · **`성공 + UNREGISTERED + UNAVAILABLE` → transient 이고 그 UNREGISTERED 만 꺼짐** · 전부 `UNREGISTERED` → permanent + 전부 꺼짐 · 전부 `UNAVAILABLE` → transient · `INVALID_ARGUMENT` → permanent인데 **token 은 안 꺼짐** · `SENDER_ID_MISMATCH` → 안 꺼짐 · 살아 있는 token 0 → permanent(`no-active-token`) · 상한 초과 시 **하나도 안 꺼짐** · 결과 순서가 token 순서와 대응 · 예외 문구에 token 문자열 없음 |
| `FcmMessageFactoryTest` (단위) | Android 는 duration, **APNs 는 epoch 초**(값이 서로 다르다) · `expiresAt` 이 과거면 0/현재로 clamp · `ttlMax` 상한 · collapse key ≤64바이트 · **collapse key 에 체크포인트가 없다** · `data` 가 허용 목록뿐이고 상태값(`status`·`transferSlackSeconds`)이 없다 · 값이 전부 문자열 · payload 가 null·비객체여도 안 터진다 |
| `FirebaseAdminMessagingClientTest` (단위) | 500 이하 → 1회 호출 · 501 → 2회 호출이고 결과 길이·순서가 입력과 같다 · 전체 deadline 초과 → transient |
| `PushTokenDeactivationServiceTest` / IT | 조건부 UPDATE — `lastSeenAt` 이 바뀐 행은 **안 꺼진다**(0행 정상) · 소유자가 바뀐 행도 안 꺼진다 · 상한 초과면 0건 |
| `JourneyNotificationFcmRelayIT` | outbox 행 → relay → 가짜 client → `SENT` · 죽은 token 이 DB 에서 실제로 꺼짐 · transient 면 `PENDING` 복귀 + `scheduled_at` 이 밀림 |
| `JourneyNotificationPropertiesTest` (수정) | `sender=fcm` + 자격증명 없음 거절 · **타임아웃 검증이 단위를 맞춘다**(기본값이 통과해야 한다) · `ttl-max > max-ttl` 거절 |
| `FirebaseConfigTest` | `sender=noop` 이면 `FirebaseApp` 초기화가 아예 안 돈다 |

### 실행
```
./gradlew test
./gradlew test --tests 'watoo.grd.nextroute.application.notification.*'   # 버스 회귀
```

---

## 10. 참고 파일 (기존 코드)

| 용도 | 경로 |
|---|---|
| 포트 계약(행 = 사용자 × 채널) | `application/journey/port/out/NotificationSender.java` |
| 발송 결과 분류를 부르는 자리 | `application/journey/service/JourneyNotificationRelayService.java:120` |
| 트랜잭션 경계 관례 | `application/journey/service/JourneyNotificationOutboxStateService.java` |
| token 조회·재등록 upsert | `domain/push/repository/UserPushTokenRepository.java:53` |
| token 수명 메서드 | `domain/push/entity/UserPushToken.java:90-105` |
| 외부 API 실패 분류 원본 | `infrastructure/adapter/out/api/toss/TossMessengerAdapter.java:38-63` |
| 설정 검증 관례 | `application/journey/config/JourneyNotificationProperties.java:102-129` |
| 시크릿 배선 | `compose.yaml:9-16`(공용 앵커 — 여기 넣지 말 것), `compose.yaml:85-91`(worker) |
| 가짜 포트 원본 | `src/test/java/.../notification/CountingNotificationSender.java` |
| push payload 계약 | `docs/plan/journey-navigation/REQUIREMENTS.md:171-176` |

---

## 11. 이 PR 이 하지 않는 것

| 항목 | 담당 | 이 PR 이 준비해 두는 것 |
|---|---|---|
| 체크포인트 종류 확장·발송 금지 규칙 | ④ | 체크포인트별 문구가 한 곳에 모여 있다 |
| lease·version 발송 직전 재검증 | ⑤ | `NotificationSendCommand` 에 두 version 을 이미 싣는다 |
| payload 계약·deep link·최종 문구 | ⑥ | `FcmMessageFactory` 한 곳만 고치면 되게 모아 둠 |
| alert-to-action 지표 | ⑦ | `pushTokens{outcome}`·`tokenDeactivated` |
| 운영 발송 활성화 | ⑤·⑥ 이후 | 게이트를 §6 과 PHASE-5 에 적는다 |
| dry-run / canary 발송 경로 | 후속 | 하지 않는 이유를 §1 에 적음 |
| 사용자당 활성 token 상한 | 후속 | chunk 로 상한 없이도 동작은 한다 |
| 토스 메신저 채널 라우팅 | (미정) | `NotificationChannelResolver` 포트와 `TOSS` 채널 값이 이미 있다 |

---

## 12. codex 판단 (2026-08-10) — 쟁점 6개 확정

| # | 쟁점 | 결론 | 반영 자리 |
|---|---|---|---|
| 1 | 살아 있는 token 0건 | **`FAILED`** + 사유 `no-active-token` + 별도 지표 | §1, §2.3, Step 7 |
| 2 | 전부 영구 실패 | **`FAILED`**. `SENT` 는 "한 대라도 접수됐다"일 때만 | §2.3, Step 5(포트 문구 정정) |
| 3 | `notification` 블록 | **넣는다.** 단 운영 활성화는 ⑥ 이후로 막는다 | §1, §6 |
| 4 | `environment` 컬럼 | **안 쓴다.** "배포 하나 = Firebase 프로젝트 하나" 불변식을 Javadoc 에 | §1, Step 4 |
| 5 | DTO 확장 | **확장한다.** version 둘까지 같이 싣는다(⑤·⑥ 대비) | Step 6 |
| 6 | multicast 500 상한 | **지금 처리한다.** chunk + 순서 재조립 + 행 전체 deadline | §4.1, Step 2 |

---

## 13. codex 가 잡은 막는 문제 9개 — 반영 내역

| # | 지적 | 반영 |
|---|---|---|
| B1 | 성공 1건이면 바로 반환 → `성공 + UNAVAILABLE` 이 `SENT` 가 되어 그 기기는 영구 누락 | **transient 우선** 규칙으로 종결 순서를 다시 씀(§2.3) + 전용 테스트 2개 |
| B2 | `INVALID_ARGUMENT`·`SENDER_ID_MISMATCH` 로 token 을 끄면 설정 실수가 주소록을 전멸시킴 | 비활성화 대상을 **`UNREGISTERED` 하나로** 축소 + 대량 손상 방지 상한(§2.2) |
| B3 | 발송 도중 재등록되면 `WHERE id IN (...)` 이 새 등록을 꺼 버림(TOCTOU) | **조건부 UPDATE**(관측한 `userId`·`lastSeenAt`)로 바꿈(§2.8) |
| B4 | Android TTL(남은 시간)과 APNs `apns-expiration`(epoch 초)의 단위가 다름 | 값 하나에서 두 단위로 파생하도록 명세(§2.7) + 헤더 값을 직접 보는 테스트 |
| B5 | payload 평탄화가 "push 는 상태 저장소가 아니다"를 위반 | `data` **허용 목록**만 싣도록 못박음(§2.6) |
| B6 | ⑤ 가 없는데 이 PR 배포 절차가 relay 를 켬 | **활성화 게이트** 신설 — 이 PR 은 스위치를 안 올린다(§6) |
| B7 | `dry-run=true` 가 진짜 행을 `SENT` 로 소모 | **옵션 자체를 제거**. canary 는 후속 과제(§11) |
| B8 | 시크릿·초기화가 API 컨테이너에도 들어감 | **worker 서비스에만** 배선(§1, Step 8) |
| B9 | `timeout-ms * 2 < claim-timeout-minutes` 는 단위가 달라 정상 설정도 부팅 실패 | `Duration` 비교로 수정(§4.5) |

---

## 14. 진행 방식

1. ~~이 PLAN.md 로 codex 논의~~ → **완료**(2026-08-10, §12·§13)
2. **새 세션**에서 이 파일을 읽고 §3 순서대로 구현. ② PR #123 머지 후 fresh main 에서 시작
3. `/verify` + 리뷰 → PR (플랜 파일 2개 포함)
