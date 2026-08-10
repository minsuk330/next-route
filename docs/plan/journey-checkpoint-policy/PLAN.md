# 여정 체크포인트 정책 (Phase 5 ④, L) — 실행 플랜

- 슬러그: `journey-checkpoint-policy`
- 브랜치: `feat/journey-checkpoint-policy` (**fresh main 기준 독립 브랜치**)
- 마이그레이션: **없음**
- 선행: ②([journey-notification-outbox](../journey-notification-outbox/SUMMARY.md)) — main 머지 완료
- 무관: ③(PR #126, FCM sender)은 발송기 쪽이라 이 PR 과 파일이 겹치지 않는다. ③ 머지를 기다리지 않는다
- 설계 근거 전문: `docs/superpowers/specs/2026-08-10-journey-checkpoint-policy-design.md`(로컬 전용, `docs/*` 는 gitignore)
- 상위: [PHASE-5.md](../journey-navigation/PHASE-5.md) §④
- **codex 설계 리뷰 반영 완료(2026-08-10). 지적 7개의 처분은 §13**

## 0. 한 줄

**갈아탈 차가 곧 온다는 알림(N2) 하나를 새로 만들고, 그것이 기존 위험 알림과 서로를 밟지 않도록
취소 규칙을 비대칭으로 나눈다.**

## 1. 확정 사항 (구현 중 재논의 금지)

| # | 결정 | 이유 |
|---|---|---|
| D1 | 체크포인트는 **3개에서 멈춘다** — `TRANSFER_RISK_AT_RISK`·`TRANSFER_RISK_MISSED`·`TRANSFER_TARGET_APPROACHING` | N4·N5·N8 은 사건이 아니라 내용이다. ⑥ payload·앱 화면 소유 |
| D2 | N1·N3(하차·정류장 접근)은 **어휘에 두지 않는다** | 모바일 local notification 소유. 규칙이 아니라 타입으로 막는다 |
| D3 | N2 상태 조건은 `COMFORTABLE`·`CATCHABLE` **둘 다** | 일찍 도착해 기다리면 여유가 커져 `COMFORTABLE` 이 된다. 그때가 N2 가 가장 쓸모 있는 순간 |
| D4 | N2 억제는 **`deliveryKey` unique 만**. 마커 컬럼 없음 | 마커는 상태 서열 축이고 N2 는 그 축이 아니다. 새 칸 = 마이그레이션 |
| D5 | **회복 경로는 N2 를 보호**하고, **승격 경로는 PENDING N2 를 취소**한다 | 비대칭. §4 |
| D6 | N2 만료는 `nextBusArrivalAt` 그대로. **바닥·천장 clamp 없음** | 버스 떠난 뒤 "곧 온다"는 틀린 알림. 안 가는 편이 낫다 |
| D7 | 클래스를 나눈다 — `TransferTargetApproachNotifier` 신규 | N2 경로는 `transferRepository`·마커·서열 비교를 하나도 안 쓴다 |
| D8 | `JourneyOutboxEnqueuedEvent` 발행을 **writer 로 끌어올린다**. 발행 조건은 **행이 실제로 생겼을 때**다 | 평가 1회당 최대 1번. 중복 틱마다 relay 를 깨우지 않는다 |
| D9 | 트리거 판정은 **`TransferTargetApproachPolicy` 한 곳**이 소유한다 | 평가 서비스(채널 결정 여부)와 판정기(enqueue 여부)가 같은 조건을 봐야 한다 |
| D10 | 평가 한 번의 `now` 를 **하나만 캡처**해 판정·enqueue 에 함께 넘긴다 | 두 번 읽으면 조건을 통과한 알림이 삽입 시점에 이미 만료일 수 있다 |
| D11 | 이 PR 도 운영 발송을 켜지 않는다 (`sender=noop`·`relay.enabled=false` 유지) | ⑤·⑥ 이 먼저다 |

## 2. N2 트리거 계약

셋 전부 AND 일 때만 쌓는다.

```
1) currentLegIndex == assessment.toLegIndex                   // 그 환승 대기 단계에 들어옴
2) assessment.status() ∈ {COMFORTABLE, CATCHABLE}             // 위험 알림과 안 겹침
3) assessment.nextBusArrivalAt() != null
   && 0 < (nextBusArrivalAt - now) <= approachWindow          // 기본 120초, 오른쪽 닫힘
```

- 조건 1 은 `TransferRiskAssessment#isUpcoming` 이 아니라 **정확한 동등 비교**다. `isUpcoming` 은
  `toLegIndex >= currentLegIndex` 라 아직 앞 차를 타고 있는 환승까지 통과시킨다
- 조건 3 의 왼쪽은 열린 구간(`0 <`), 오른쪽은 닫힌 구간(`<= window`)이다. 도착이 이미 과거면 보내지
  않고, 정확히 120초면 보낸다
- `UNKNOWN` 은 `nextBusArrivalAt` 이 null 이라 조건 3 에서 저절로 떨어진다 — 상태 목록에 넣지 않는다
- `now` 는 **평가 한 번에 하나**다(D10)

### 2.1 판정 소유자 (신규)

`src/main/java/watoo/grd/nextroute/application/journey/service/TransferTargetApproachPolicy.java`

```java
@Component
@RequiredArgsConstructor
public class TransferTargetApproachPolicy {

    private static final Set<TransferStatus> SAFE =
            EnumSet.of(TransferStatus.COMFORTABLE, TransferStatus.CATCHABLE);

    private final JourneyNotificationProperties properties;

    /** §2 의 세 조건. 저장소를 보지 않고 아무것도 저장하지 않는다. */
    public boolean isApproaching(TransferRiskAssessment assessment, int currentLegIndex, Instant now) { ... }
}
```

이 클래스가 있어야 하는 이유는 §8.1 이다 — 평가 서비스가 "알릴 것이 있나"를 판단할 때와 판정기가
"쌓을까"를 판단할 때 **같은 조건**을 봐야 한다. 두 벌로 두면 한쪽만 고쳐도 아무 데서도 안 터지고,
N2 가 조용히 안 나가는 상태가 된다(codex 지적 4번이 정확히 그 사고였다).

## 3. 어휘

`src/main/java/watoo/grd/nextroute/domain/journey/entity/JourneyCheckpoint.java`

```java
/** 갈아탈 차가 곧 온다(N2). 위험이 아니라 행동 신호다 — 안전한 상태에서만 나간다. */
TRANSFER_TARGET_APPROACHING;
```

- `ofTransferStatus(TransferStatus)` 는 **그대로 둔다**. 그 메서드는 위험 축 전용이고 N2 는 상태에서
  파생되지 않는다(트리거가 상태 + 시간 + 단계의 곱이다)
- Javadoc 에 "하차·정류장 접근은 이 어휘에 없다 — 모바일 소유"를 명시한다

## 4. 비대칭 취소 (이 PR 의 핵심)

`src/main/java/watoo/grd/nextroute/domain/journey/repository/JourneyNotificationRepository.java`

| 메서드 | 줄(현재) | 부르는 곳 | N2 | 조치 |
|---|---|---|---|---|
| `supersedePendingByScope` | ~102 | 승격(`TransferRiskNotifier#handle`) | PENDING 만 취소 | **손대지 않는다** |
| `supersedeAllPendingByScope` | ~125 | 회복(`TransferRiskNotifier#recoverIfImproved`) | 보호 | `checkpoint` 필터 추가 |

회복 쿼리에 한 줄을 더한다.

```java
   and n.checkpoint in :riskCheckpoints
```

시그니처:

```java
int supersedeAllPendingByScope(@Param("journeyId") Long journeyId,
                               @Param("routeRevision") int routeRevision,
                               @Param("scopeType") JourneyNotificationScope scopeType,
                               @Param("scopeId") int scopeId,
                               @Param("riskCheckpoints") Collection<JourneyCheckpoint> riskCheckpoints);
```

- `checkpoint` 컬럼이 엔티티에서 어떤 타입인지 먼저 확인한다(`JourneyNotification`). `String` 으로
  저장돼 있으면 파라미터도 `Collection<String>` 이어야 한다 — **타입을 맞추지 않으면 조건이 조용히
  0건이 되어 회복이 아무것도 안 지운다**
- 호출부(`TransferRiskNotifier#recoverIfImproved`)에 위험 체크포인트 집합 상수를 둔다:
  `EnumSet.of(TRANSFER_RISK_AT_RISK, TRANSFER_RISK_MISSED)`
- 승격 쿼리를 안 고치는 이유를 Javadoc 에 남긴다: 쿼리가 이미 `PENDING` 만 건드리므로 그 의미가
  그대로 "아직 발송되지 않은 N2 만 취소"가 된다

### 4.1 이 필터는 불변식 가드다 (codex 2번)

정상 전이에서는 **이 필터가 실제로 N2 를 구하는 일이 없다.** 순서를 따라가 보면 그렇다.

1. 위험 알림이 나가면 마커가 찍히고, 그 순간 승격 supersede 가 그 환승의 PENDING N2 를 지운다
2. 나중에 상태가 좋아져 회복 경로가 도는 시점에는 이미 N2 가 `CANCELED` 다
3. 같은 평가에서 회복이 먼저 돌고(§8) 마커가 비워진 뒤에 N2 가 쌓이므로, 다음 평가의 회복 경로는
   `lastNotifiedStatus == null` 로 일찍 빠져나간다

그래도 필터를 넣는다. 순서(§8)가 유일한 보호막이면 나중에 배선을 바꾸는 사람이 조용히 N2 를
지운다. **필터는 "회복은 위험 알림만 지운다"는 불변식을 코드에 적어 두는 장치다.**

테스트도 그 성격에 맞춘다 — 정상 전이로 이 상황을 만들 수 없으므로 N2 PENDING 행을 **직접
seed** 해서 회복 경로를 부른다(§10 의 8번). 전체 전이의 기대값은 따로 고정한다(8b).

## 5. 설정

`src/main/java/watoo/grd/nextroute/application/journey/config/JourneyNotificationProperties.java`

```java
private final Approach approach = new Approach();

@Getter @Setter
public static class Approach {
    /** N2 만 끄는 kill switch. 위험 알림은 그대로 간다. */
    private boolean enabled = true;
    /** 이 시간 안에 도착하면 "곧 온다"로 본다. N2 의 유효시간 상한이기도 하다. */
    private long windowSeconds = 120;

    public Duration window() { return Duration.ofSeconds(windowSeconds); }
}
```

`validate()` 에 추가:

```java
if (approach.getWindowSeconds() <= 0) {
    throw new IllegalStateException("journey.notification.approach.window-seconds 는 0 보다 커야 한다");
}
if (approach.getWindowSeconds() > maxTtlSeconds) {
    throw new IllegalStateException(
            "approach.window-seconds 는 max-ttl-seconds 이하여야 한다. 넘으면 접근 알림만 "
                    + "천장보다 오래 살아 알림 수명의 상한이 두 벌이 된다");
}
```

`src/main/resources/application.yaml` — `journey.notification` 블록(387~) 안, `relay` 앞:

```yaml
    approach:
      # 갈아탈 차가 곧 온다는 알림(N2)만 끄는 스위치. 위험 알림은 그대로 간다.
      enabled: ${JOURNEY_NOTIFICATION_APPROACH_ENABLED:true}
      # 이 시간 안에 도착하면 "곧 온다"로 본다. 이 값이 N2 의 유효시간 상한이다
      # (접근 알림은 바닥·천장 clamp 없이 다음 차 도착 시각을 그대로 만료로 쓴다).
      window-seconds: 120
```

## 6. enqueue 경로

`src/main/java/watoo/grd/nextroute/application/journey/service/JourneyNotificationEnqueuer.java`
— 메서드 하나 추가. 기존 `enqueueTransferRisk`(48~70)는 손대지 않는다.

```java
/**
 * 환승 대상 차량 접근 알림 한 건(N2).
 *
 * <p>만료는 다음 차 도착 시각 <b>그대로</b>다. 위험 알림의 180초 바닥을 쓰지 않는다 — 그 바닥은
 * MISSED 의 도착이 이미 과거라서 필요한 장치이고, 여기서는 부르는 쪽이 "도착이 미래이고 창 안"을
 * 이미 보장한다. 바닥을 붙이면 버스가 떠난 뒤에 "곧 온다"가 나간다.
 *
 * <p><b>발송 키가 아니라 생성 여부를 돌려준다.</b> 위험 알림은 밀어내기 대상에서 빼려고 키가
 * 필요했지만 N2 는 supersede 를 부르지 않는다. 대신 "행이 실제로 생겼는가"가 필요하다 —
 * 그것이 relay 를 깨울지의 기준이다(D8).
 *
 * @param now 평가 한 번의 기준 시각. 부르는 쪽이 조건 검사에 쓴 것과 같은 값이어야 한다
 * @return 행이 새로 생겼으면 true. 중복이라 0행이면 false
 */
public boolean enqueueTargetApproach(long journeyId, int routeRevision,
                                     TransferRiskAssessment assessment,
                                     NotificationChannel channel, Instant now) { ... }
```

구현 뼈대:

- `checkpoint = JourneyCheckpoint.TRANSFER_TARGET_APPROACHING` (상태에서 파생하지 않는다)
- `logicalKey`·`deliveryKey` 는 기존 `JourneyNotificationKeys` 로 조립(scope = `TRANSFER`,
  scopeId = `assessment.transferIndex()`)
- `expiresAt = assessment.nextBusArrivalAt()` — clamp 없음
- payload 는 기존 `writePayload(assessment)` 재사용(private → 그대로 씀)
- `notificationRepository.enqueue(...)` 결과가 비지 않으면 `metrics.recordEnqueued(checkpoint)` 하고
  `true`, 비면 `false`
- `Instant.now(clock)` 을 **여기서 다시 읽지 않는다**. `now` 는 인자로 받는다(D10)

## 7. 신규 판정기

`src/main/java/watoo/grd/nextroute/application/journey/service/TransferTargetApproachNotifier.java`

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferTargetApproachNotifier {

    private final JourneyNotificationEnqueuer enqueuer;
    private final TransferTargetApproachPolicy policy;
    private final JourneyNotificationProperties properties;
    private final Clock clock;

    /** @return 접근 알림 행이 <b>실제로 생겼으면</b> true */
    public boolean notifyApproach(JourneyEvaluationOutcome outcome, NotificationContext context) { ... }
}
```

규칙:

- `properties.isEnqueueEnabled()` 가 false 거나 `properties.getApproach().isEnabled()` 가 false 면
  즉시 false
- `context == null || context.channels().isEmpty()` 면 즉시 false
- 메서드 첫머리에 `Instant now = Instant.now(clock)` 를 **한 번만** 읽어 판정과 enqueue 에 함께
  넘긴다(D10)
- `outcome.batch().assessments()` 를 돌며 `policy.isApproaching(assessment, context.currentLegIndex(), now)`
  가 참인 것마다 채널별로 `enqueuer.enqueueTargetApproach(...)` 를 부르고, **하나라도 true 면 반환값이
  true** 다
- **supersede 를 부르지 않는다.** N2 는 자기 만료로만 사라진다
- **마커를 쓰지 않는다.** `transferRepository` 를 주입하지 않는다 — 의존성이 없다는 사실이 이
  클래스의 계약이다
- `try/catch` 로 감싸지 않는다(②와 같은 자세). rollback-only 트랜잭션에서 예외를 삼키면 시끄러운
  실패가 조용한 실패로 바뀔 뿐이다

## 8. 배선

`src/main/java/watoo/grd/nextroute/application/journey/service/JourneyEvaluationScheduleWriter.java`
(`finish`, 56~73)

```java
assessmentWriter.write(...);
boolean riskEnqueued = transferRiskNotifier.notifyWorsened(outcome, notificationContext);
boolean approachEnqueued = approachNotifier.notifyApproach(outcome, notificationContext);
if (riskEnqueued || approachEnqueued) {
    eventPublisher.publishEvent(new JourneyOutboxEnqueuedEvent());
}
storeBoardingPredictions(outcome);
```

- **순서를 지킨다: 위험 → 접근.** 회복 경로(`recoverIfImproved`)가 같은 환승 scope 의 PENDING 을
  지우는데, 접근이 먼저 돌면 방금 넣은 N2 가 그 대상이 된다. §4 의 필터가 이미 막지만 순서로도
  막아 방어를 두 겹으로 둔다
- 같은 환승에서 **같은 평가 결과로** 두 알림이 enqueue 되는 일은 없다(위험은 `AT_RISK`·`MISSED`,
  접근은 `COMFORTABLE`·`CATCHABLE`). 다만 **이것이 "relay 가 둘을 동시에 들고 있지 않다"를 뜻하지는
  않는다** — 앞선 평가가 쌓아 둔 N2 가 아직 `PROCESSING` 일 수 있다(§11 의 경쟁 창)
- `TransferRiskNotifier` 는 `ApplicationEventPublisher` 의존을 **제거**하고 `boolean` 을 반환한다
  (기존 100~104 의 발행 블록 삭제, `enqueuedAny` 를 그대로 return)

### 8.1 채널 결정 조건 확장 (codex 4번 — 이걸 빼면 N2 가 아예 안 나간다)

`src/main/java/watoo/grd/nextroute/application/journey/service/JourneyEvaluationService.java`
(`notificationContext`, 123~134)

지금 이 메서드는 **`AT_RISK`·`MISSED` 인 upcoming 환승이 하나라도 있을 때만** context 를 만들고,
없으면 `null` 을 넘긴다. N2 는 안전 상태에서만 나가므로 이대로 두면 판정기가 `context == null` 로
매번 즉시 반환한다 — **N2 는 코드가 다 있어도 한 건도 나가지 않는다.**

조건을 이렇게 고친다.

```java
boolean anyRisk = batch.assessments().stream()
        .anyMatch(a -> (a.status() == TransferStatus.AT_RISK || a.status() == TransferStatus.MISSED)
                && a.isUpcoming(context.currentLegIndex()));
boolean anyApproach = approachPolicy.isApproaching(...);   // batch 를 돌며 하나라도 참인지
if (!anyRisk && !anyApproach) {
    return null;
}
```

- `now` 는 `evaluate(...)` 가 이미 들고 있는 값을 그대로 쓴다(D10)
- 판정을 다시 적지 않고 `TransferTargetApproachPolicy` 를 주입해 부른다(D9)
- "대부분의 평가는 여기서 null 이라 채널 조회가 안 붙는다"는 기존 주석의 취지는 유지된다 — N2 조건이
  좁아(대기 단계 + 임박 창) 대부분의 평가는 여전히 null 이다

## 9. 작업 순서

- [ ] **S1** `JourneyCheckpoint` 에 `TRANSFER_TARGET_APPROACHING` 추가 + Javadoc(§3)
      → `./gradlew compileJava`
- [ ] **S2** `JourneyNotificationProperties.Approach` + `validate()` 규칙 둘 + `application.yaml`(§5)
      → `./gradlew compileJava`
- [ ] **S3** `TransferTargetApproachPolicy` 신규(§2.1) + 단위 테스트(경계 0·120·121초)
- [ ] **S4** `JourneyNotificationEnqueuer#enqueueTargetApproach`(§6, `boolean` 반환·`now` 인자)
      → `./gradlew compileJava`
- [ ] **S5** `JourneyNotificationRepository#supersedeAllPendingByScope` 필터 + 호출부 수정(§4).
      **엔티티의 `checkpoint` 필드 타입을 먼저 확인**
      → `./gradlew test --tests '*JourneyNotification*'`
- [ ] **S6** `TransferRiskNotifier` 에서 이벤트 발행 제거 → `boolean` 반환(§8)
- [ ] **S7** `TransferTargetApproachNotifier` 신규(§7)
- [ ] **S8** `JourneyEvaluationScheduleWriter#finish` 배선(§8) + **`JourneyEvaluationService#notificationContext`
      조건 확장(§8.1)**
      → `./gradlew compileJava compileTestJava`
- [ ] **S9** 테스트(§10). 기존 IT 중 이벤트 발행을 검증하던 것이 있으면 writer 기준으로 옮긴다
- [ ] **S10** 전체 회귀 `./gradlew test`
- [ ] **S11** `PHASE-5.md` ④ 절에 구현 완료 표시 + 이 PR 이 굳힌 계약(어휘 3개·비대칭 취소·경쟁 창)
      반영. ⑤ 절에 "N2 발송 직전 최신 환승 상태 확인" 항목 추가(§11)
- [ ] **S12** 커밋(플랜 파일 포함) → PR

## 10. 테스트

- 단위: `TransferTargetApproachPolicyTest`
- 통합: `src/test/java/watoo/grd/nextroute/application/journey/notification/JourneyTargetApproachEnqueueIT.java`
  (②의 `JourneyNotificationEnqueueIT` 픽스처 관례를 따른다)

| # | 시나리오 | 기대 | 왜 |
|---|---|---|---|
| 1 | 대기 leg + 도착 60초 뒤 + `CATCHABLE` | 1행, checkpoint `TRANSFER_TARGET_APPROACHING` | 기본 |
| 2 | 대기 leg + 도착 60초 뒤 + **`COMFORTABLE`** | 1행 | 일찍 도착 케이스. 가장 잘 빠지는 자리 |
| 3 | 같은 조건 10초 간격 6회 | 여전히 1행, **2회차부터 wake 이벤트 0** | 키 dedupe + D8 |
| 4 | `AT_RISK` + 도착 60초 뒤 | 접근 0행(위험 1행) | 중복 방지 |
| 5 | `currentLegIndex < toLegIndex` | 0행 | 앞 차 타는 중 |
| 6 | 도착 **0초·정확히 120초·121초** (단위) | 0행·1행·0행 | 경계. 150초만 보면 포함 경계를 못 본다 |
| 7 | 1번 행의 `expiresAt` | `== nextBusArrivalAt`(바닥 미적용) | D6 |
| 8 | N2 PENDING 을 **직접 seed** → 회복 경로 호출 | 위험 PENDING 만 CANCELED, **N2 생존** | 불변식 가드(§4.1) |
| 8b | 정상 전이: N2 PENDING → `AT_RISK` 승격 → `CATCHABLE` 회복 | 승격에서 N2 CANCELED, 회복 후에도 **재생성 없음** | 평생 1회 계약을 고정 |
| 9 | N2 PENDING + N2 PROCESSING → `CATCHABLE`→`AT_RISK` 승격 | PENDING 은 CANCELED, **PROCESSING 무손상** | 비대칭 나머지 |
| 10 | 위험·접근이 서로 다른 환승에서 동시에 쌓임 | `JourneyOutboxEnqueuedEvent` 1회 | D8 |
| 11 | `JourneyCheckpoint.values().length` | `== 3` | 하차·정류장 접근 발송 0(D2) |
| 12 | `approach.enabled=false` | 접근 0행, 위험 알림 정상 | kill switch |
| 13 | **안전 상태만 있는 평가**(위험 0건) + 임박 | `notificationContext` 가 만들어지고 N2 1행 | §8.1. **이게 없으면 4번 사고를 못 잡는다** |
| 14 | N2 enqueue 가 예외를 던짐 | 평가 트랜잭션 rollback, 판정 저장도 안 남음 | §11 실패 정책을 계약으로 고정 |

- 8·9 는 비대칭 규칙의 방어선, **13 은 N2 가 살아 있는지의 방어선**이다
- 6·11 은 단위 테스트로 한다. 테스트 컨테이너가 무거워 IT 수를 늘리면 전체 시간이 늘어난다

회귀 기준: ②의 IT 전부(`JourneyNotificationEnqueueIT`·`RelayIT`·`AtomicClaimIT`·`CancelOnEndIT`) +
버스 도착 알림·토스 발송 경로 무손상. `./gradlew test` 전체 통과.

## 11. 수용하는 위험 — PROCESSING N2 경쟁 창 (codex 1·3번)

승격은 `PENDING` N2 만 취소한다. 이미 claim 돼 `PROCESSING` 인 N2 는 못 되돌린다. relay 의 발송 직전
재확인(`JourneyNotificationOutboxStateService#loadTask`, 76~103)은 여정 활성·`routeRevision`·만료만
보고 **최신 환승 상태는 보지 않는다.** 따라서 이런 순서가 가능하다.

```
t0  N2 claim (PENDING → PROCESSING)
t1  평가: CATCHABLE → AT_RISK, 위험 알림 enqueue (PENDING N2 없음 — 이미 PROCESSING)
t2  N2 발송  ← "곧 온다"가 위험 알림과 겹치거나 뒤에 도착
```

**이 PR 은 이것을 수용하고 문서로 남긴다.** 창이 좁고(claim→발송, 보통 수 초), 막으려면 발송 직전에
환승 상태를 다시 읽어야 하는데 그것은 ⑤(`journey-notification-guards`)가 lease·version 재검증을
넣는 자리다. **S11 에서 PHASE-5 ⑤ 절에 "N2 는 발송 직전 최신 환승 상태도 확인한다"를 명시한다.**

곁들여 §8 의 문장을 정확히 적는다 — 배타성은 "같은 평가 결과에서 동시에 enqueue 되지 않는다"까지이고,
relay 단계의 동시 보유까지 보장하지 않는다.

### 11.1 enqueue 실패와 발송 실패는 다르다 (codex 7번)

PHASE-5 §3 의 "알림 발송 실패가 여정을 실패로 만들면 안 된다"는 **relay·provider 단계**의 이야기다.
평가 트랜잭션 안의 enqueue 실패는 그것과 다르다.

| 층 | 실패하면 | 근거 |
|---|---|---|
| enqueue(평가 트랜잭션) | 평가 전체 rollback. 삼키지 않는다 | rollback-only 트랜잭션에서 예외를 삼키면 시끄러운 실패가 조용한 실패로 바뀐다(②와 동일) |
| relay·provider | 알림만 재시도·DEAD. 여정은 무관 | ②가 이미 그렇게 구현 |

payload 직렬화 실패만 예외다 — 기존 `writePayload` 가 `null` 을 돌려주고 알림을 살린다(문구가 밋밋할
뿐이다). rollback 뒤에는 다음 평가 tick(기본 10초)이 같은 조건을 다시 만나 재시도한다. 임박 창이
120초라 최소 열 번 이상의 기회가 남는다. 14번 테스트가 이 계약을 고정한다.

## 12. 함정

- **`checkpoint` 파라미터 타입** — 엔티티가 문자열로 들고 있으면 enum 컬렉션을 넘길 때 조건이
  조용히 0건이 된다. 회복이 아무것도 안 지우는데 테스트가 없으면 안 잡힌다(8번이 잡는다)
- **`clearAutomatically = false` 를 유지한다.** 부르는 쪽이 같은 트랜잭션에서 `TransferConnection`
  마커를 고치고 있어 영속성 컨텍스트를 비우면 그 변경이 사라진다(②의 결정)
- **`isUpcoming` 을 재사용하지 않는다**(§2 조건 1)
- **`notificationContext` 를 고치는 것을 잊지 마라**(§8.1). 나머지를 다 만들어도 N2 는 0건이고,
  통합 테스트가 위험 알림 픽스처를 쓰면 그 사실이 안 드러난다
- **평가 트랜잭션 안이다.** 판정에 쓰는 값은 전부 이미 계산된 DTO 필드로 제한한다 — 저장소를 새로
  읽지 않는다
- **`Clock` 을 주입해 쓰고, 평가당 한 번만 읽는다**(D10)
- 임박 창 동안 환승당 최대 12회(120초 ÷ 10초) 헛삽입이 난다. 행은 안 늘고 wake 이벤트도 D8 로
  1회지만, Phase 4 부하 하네스에서 enqueue 시도 수를 한 번 확인한다

## 13. codex 리뷰 처분 (2026-08-10)

| # | 지적 | 처분 |
|---|---|---|
| 4 | `notificationContext()` 가 위험 상태에서만 만들어져 **N2 가 한 건도 안 나간다** | **수용 — 최우선.** §8.1 신설, 판정을 `TransferTargetApproachPolicy` 로 단일화(D9), 13번 테스트 추가 |
| 1 | 승격 뒤 `PROCESSING` N2 가 발송될 수 있다 | **수용(문서화·이관).** §11 로 경쟁 창을 명시하고 발송 직전 상태 재검증은 ⑤ 로 넘긴다 |
| 5 | 반환값·wake 이벤트가 "시도"인지 "생성"인지 불명확 | **수용.** enqueue 는 `boolean created` 를 돌려주고 wake 는 생성됐을 때만(D8). 3번 테스트에 "2회차부터 이벤트 0" 추가 |
| 6 | `now` 기준 시점이 두 곳에서 각각 읽힌다 / 경계 시험이 포함 경계를 안 본다 | **수용.** D10 으로 단일 `now` 를 강제, 6번 테스트를 0·120·121초로 교체 |
| 2 | 테스트 8 의 전이는 정상 경로로 성립하지 않는다 | **수용.** §4.1 로 "불변식 가드"임을 명시하고, 8 은 직접 seed·8b 는 정상 전이 기대값으로 분리 |
| 3 | "동시에 안 나간다"가 outbox 단계까지 확장되지 않는다 | **수용.** §8 문장을 "같은 평가 결과에서"로 한정 |
| 7 | enqueue 실패와 발송 실패의 정책 경계가 불명확 | **수용.** §11.1 표로 층을 갈라 적고 14번 테스트로 고정 |

## 14. 범위 밖

- N4·N5·N8 문구·표시 — ⑥ `journey-push-payload-deeplink`
- 발송 직전 lease·revision·version·**최신 환승 상태** 재검증 — ⑤ `journey-notification-guards`
- 대체 경로 제안 — Phase 6 재탐색
- 운영 발송 활성화 — ⑤·⑥ 이후(D11)
