# journey-reroute-trigger — 구현 계획 (Phase 6 ①, M티어)

- 상위 인덱스: [PHASE-6.md](../journey-navigation/PHASE-6.md) §① · [PLAN.md](../journey-navigation/PLAN.md) §6.6
- 요구사항: [REQUIREMENTS.md](../journey-navigation/REQUIREMENTS.md) §4.8 (R1·R2)
- 범위 변경: [SCOPE-CHANGES.md](../journey-navigation/SCOPE-CHANGES.md) SC-2 4항 · SC-10
- 앞 PR: [journey-reroute-proposal-store](../journey-reroute-proposal-store/PLAN.md) (②, PR #133 머지)
- 브랜치: `feat/journey-reroute-trigger` (fresh `main` = `e4f1a7c` 기준 독립 브랜치)
- 작성일: 2026-08-11

## 0. 이 PR 의 범위

**"언제 재탐색을 찾아볼지"만 정한다. 아무것도 만들지 않는다.**

| 넣는다 | 안 넣는다 |
|---|---|
| 문(decision window) 판정 | 후보 생성 (③④) |
| 방아쇠 판정 두 가지(하락·통과) | 제안 저장 (②가 이미 함) |
| cooldown 120초 (Redis) | `RerouteEligibilityPolicy` 실제 구현체 등록 |
| 지표 둘 + 구조화 로그 | single-flight (§1.4) |
| | 연속 N회 후보 소실 (§1.2) |

**이 PR 은 shadow 다.** 방아쇠가 켜져도 지표와 로그만 남고 제안은 만들어지지 않는다. 켜기 전에
**실제 발생률을 먼저 재기 위해서**다 — 하루에 몇 번 도는지 모르는 채로 외부 검색을 붙이면
요금이 먼저 온다.

②가 등록한 기본 거절 빈(`JourneyRerouteConfig.disabledRerouteEligibilityPolicy`)은 **그대로 둔다.**
이 PR 이 그것을 교체하면 shadow 가 아니라 실물이 된다. 교체는 ③이 한다.

## 1. 핵심 설계 결정

### 1.1 문과 방아쇠는 다른 것이다

**문**은 "이제 찾아봐도 되는 구간에 들어왔다"는 뜻일 뿐이고, 문이 열렸다고 **아무 일도 일어나지
않는다.** 실제로 찾게 만드는 것은 방아쇠다.

둘을 섞으면 **정상적으로 잘 가는 사용자마다 외부 검색이 나간다**(SC-2 4항). 환승 정류장에
가까워지는 것은 모든 여정에서 반드시 일어나는 일이라, 그것을 방아쇠로 쓰면 재탐색이 여정 수만큼
돈다.

| | 조건 |
|---|---|
| 문 | `userReadyAt - now <= 600초` **그리고** 근거가 있다(`status` ≠ `UNKNOWN` **그리고** `confidence` ≠ `UNKNOWN`) |
| 방아쇠 ⓐ 하락 | 직전 상태보다 나빠졌고(`severity` 증가) 새 상태가 `AT_RISK` 또는 `MISSED` |
| 방아쇠 ⓑ 통과 | 직전 판정의 `nextBusArrivalAt` 이 지났는데 사용자가 아직 그 대기 단계를 못 지났다 |

**새 상태가 `UNKNOWN` 이면 절대 방아쇠가 아니다.** 이 한 줄이 이 PR 에서 가장 중요하다 — 실시간
API 가 흔들리면 판정이 `UNKNOWN` 으로 떨어지는데, 그것을 "나빠졌다"로 읽으면 **API 가 흔들릴
때마다 전 사용자에게 재탐색이 돈다.** `TransferStatus.severity()` 에서 `UNKNOWN`(2)이
`CATCHABLE`(1)보다 크다는 점이 이 함정을 실제로 만든다(그 enum 주석이 알림 쪽에도 같은 경고를
적어 뒀다).

상태 판정의 원본은 `TransferConnection.status` 다. `lastNotifiedStatus`(알림용 흔적)를 쓰면
**알림이 꺼져 있을 때 방아쇠가 통째로 죽는다.**

### 1.2 무효화는 "통과"만 인정한다 — 연속 N회 소실은 ③으로

`BusTransferTargetArrivalPredictor` 는 후보 **목록**을 보지 않는다.
`(stopId, routeId, boardingSeq)` 하나를 묻고 결과 하나를 받는다
(`BusTransferTargetArrivalPredictor.java:79`). 그래서 "정상 응답에서 그 후보가 연속 N회
사라졌다"를 세려면 **회차 카운터를 어딘가 저장해야 하는데, ①은 마이그레이션이 없는 PR 이다.**

**통과**는 카운터 없이 판정된다 — 직전 판정의 `nextBusArrivalAt` 과 `currentLegIndex` 만 있으면
되고 둘 다 이미 메모리에 있다. 연속 소실은 카운터의 집이 정해지는 ③에서 더한다.

이것은 "실시간 정보가 잠깐 안 보인다"와 "후보가 사라졌다"를 가르는 원칙(X1)을 지킨다 — 통과는
**긍정적 근거**다. 시각이 지났다는 것은 API 상태와 무관하게 참이다.

### 1.3 문은 `userReadyAt` 하나로 연다 — `remainingStops` 다리는 넣지 않는다

**PHASE-6 문서와 다른 결정이라 명시한다.** 문서는 `userReadyAt <= 600초` **또는**
`remainingStops <= 2` 로 적었다.

빼는 이유 둘.

1. **거의 함의된다.** `userReadyAt = currentLegArrivalAt + 하차버퍼 + 환승도보`
   (`TransferReadinessCalculator.java:13`)이므로 `userReadyAt` 은 언제나 `currentLegArrivalAt`
   이후다. 두 정류장 남은 버스는 `userReadyAt` 이 600초 안에 들어온다 — **두 번째 다리가 여는
   문은 첫 번째가 이미 연 문이다**
2. **값이 그 자리에 없다.** `remainingStops` 는 `JourneyLeg` 에 있고
   (`JourneyLeg.java:89`) 평가 컨텍스트는 leg 를 싣지 않는다. 넣으려면 평가마다 leg 조회가 하나
   늘거나 DTO 를 수술해야 한다 — 초당 200 평가에서 1번 이유의 신호를 위해 치를 값이 아니다

**GPS 가 끊겼을 때는 별도 코드가 필요 없다.** 앱 progress 가 끊기면 `currentLegArrivalAt` 이
비고 → `userReadyAt` 이 null 이 되고 → `confidence` 가 `UNKNOWN` 으로 떨어진다. 문이 저절로
닫힌다. **그 성질을 테스트로 못 박는다**(§4.1) — 코드가 아니라 데이터 흐름이 보장하는 것이라,
누가 나중에 `userReadyAt` 의 빈 값을 기본값으로 채우면 조용히 깨진다.

### 1.4 single-flight 는 이 PR 에 넣지 않는다

PHASE-6 은 cooldown 과 single-flight 를 ①에 함께 적었다. 그런데 single-flight 가 막는 것은
**외부 호출의 중복**이고, 그 호출은 ③이 만든다. 지금 넣으면 **아무것도 감싸지 않는 추상**이
하나 생기고, ③이 그것을 쓰려 할 때 모양이 안 맞아 결국 고쳐 쓰게 된다.

키 모양 `(journeyId, routeRevision, transferIndex, searchBucket)` 만 이 문서에 남긴다.
`application/route/concurrent/SingleFlight` 가 이미 있으므로 ③은 새로 만들 필요가 없다.

### 1.5 cooldown 은 Redis, 실패하면 닫는다

키 `journey:reroute:cooldown:{journeyId}:{rev}:{transferIndex}`, `SETNX` + TTL 120초.
`RedisBusApiQuota` 의 관용구를 그대로 따른다.

**메모리 대신 Redis 인 이유** — worker 가 둘이면 메모리 cooldown 은 worker 마다 따로 돌아 같은
환승이 최대 2배로 통과한다. shadow 에서 잰 수가 ③의 실제 호출 수와 달라지면 측정의 의미가 없다.

**Redis 가 죽으면 통과시키지 않는다(fail-closed).** `RedisBusApiQuota` 와 같은 선례다 — 여기서
열어 두면 ③이 켜진 뒤 Redis 장애가 곧 ODsay 요금이 된다. shadow 단계에서는 지표가 조금 덜
찍히는 것이 전부다.

### 1.6 방아쇠 판정은 소유권 확인 뒤에 온다

`JourneyEvaluationScheduleWriter.finish()` 가 `true` 를 준 **뒤에만** 부른다.

앞에 두면 lease 를 잃은 워커가 cooldown 을 태워 **이긴 워커의 방아쇠를 먹는다.** 알림이 CAS 뒤에
오는 것과 정확히 같은 이유다(`JourneyEvaluationScheduleWriter` 주석 참고).

그리고 **트랜잭션 밖**이다. Redis 호출을 쓰기 트랜잭션 안에 넣으면 그만큼 DB 커넥션을 붙잡는다 —
`TransferAssessmentWriter` 가 계산과 저장을 나눈 것과 같은 이유다.

## 2. 변경 파일

### 2.1 신규 — 판정 (이 PR 의 심장)

**`application/journey/dto/RerouteTriggerSignal.java`**

```java
/**
 * 재탐색을 찾아볼 만한 사건 하나.
 *
 * <p>이 PR 은 이 신호를 만들고 세기만 한다. 실제로 후보를 찾는 것은 ③④다.
 */
public record RerouteTriggerSignal(
        Long journeyId,
        int routeRevision,
        int transferIndex,
        Reason reason,
        TransferStatus previousStatus,
        TransferStatus currentStatus,
        Instant userReadyAt) {

    /** 지표 태그로 쓰인다. 값 종류가 적어야 시계열이 늘지 않는다. */
    public enum Reason {
        /** 환승 상태가 나빠졌다. */
        DEGRADED,
        /** 타려던 차의 예정 시각이 지났는데 아직 못 탔다. */
        TARGET_PASSED;

        public String tag() { return name().toLowerCase().replace('_', '-'); }
    }
}
```

**`application/journey/service/RerouteTriggerPolicy.java`**

순수 판정. 외부 의존 0, 스프링 빈이지만 상태가 없다. **단위 테스트가 전부 여기 붙는다.**

```java
public class RerouteTriggerPolicy {

    /**
     * 이번 평가에서 재탐색을 찾아볼 만한 환승들.
     *
     * @param previous  직전 판정(환승 순번 → 판정). {@code JourneyEvaluationContext.previousAssessments()}
     * @param current   이번 판정
     * @param currentLegIndex 지금 진행 중인 단계
     */
    public List<RerouteTriggerSignal> signals(Long journeyId, int routeRevision,
                                              Map<Integer, TransferRiskAssessment> previous,
                                              List<TransferRiskAssessment> current,
                                              int currentLegIndex, Instant now);
}
```

판정 순서(한 환승마다):

1. `assessment.isUpcoming(currentLegIndex)` 아니면 제외 — 이미 지나온 환승은 찾아 봐야 소용없다
2. **문**: `userReadyAt != null` && `Duration.between(now, userReadyAt) <= window` &&
   `status != UNKNOWN` && `confidence != Quality.UNKNOWN`. 닫혀 있으면 끝
3. **ⓐ 하락**: `current.status().severity() > previous.status().severity()` &&
   `current.status() ∈ {AT_RISK, MISSED}`
4. **ⓑ 통과**: `previous != null` && `previous.status() != UNKNOWN` &&
   `previous.nextBusArrivalAt() != null` && `previous.nextBusArrivalAt().isBefore(now)`
5. ⓐ·ⓑ 둘 다면 **ⓐ 하나만** 낸다 — 같은 환승에서 신호를 둘 내면 cooldown 이 하나를 삼켜
   지표가 사유별로 어긋난다. 하락이 더 구체적인 사건이므로 우선

**직전 판정이 없으면**(여정 첫 평가) ⓐ·ⓑ 둘 다 성립하지 않는다. `previous == null` 을
`UNKNOWN` 으로 취급하지 않는다 — 그러면 첫 평가에서 `UNKNOWN(2) → AT_RISK(3)` 로 읽혀 모든
여정이 시작하자마자 한 번씩 방아쇠를 켠다.

### 2.2 신규 — 포트·어댑터

**`application/journey/port/out/RerouteCooldownPort.java`**

```java
/**
 * 같은 환승에서 재탐색을 너무 자주 하지 않게 막는다.
 *
 * <p>구현이 실패하면 <b>통과시키지 않는다</b>. 여기서 열어 두면 ③이 켜진 뒤 캐시 장애가 곧
 * 외부 검색 요금이 된다.
 */
public interface RerouteCooldownPort {

    /** @return 지금 찾아봐도 되면 true. 이미 최근에 찾았거나 확인이 실패하면 false */
    boolean tryAdmit(Long journeyId, int routeRevision, int transferIndex);
}
```

**`infrastructure/adapter/out/cache/journey/RedisRerouteCooldownAdapter.java`**

`RedisBusApiQuota` 골격. `opsForValue().setIfAbsent(key, "1", cooldown)` 한 번으로 끝낸다
(`increment` + `expire` 두 문장으로 나누면 그 사이에 죽었을 때 TTL 없는 키가 남는다).

```java
static final String KEY_PREFIX = "journey:reroute:cooldown:";

@Override
public boolean tryAdmit(Long journeyId, int routeRevision, int transferIndex) {
    String key = KEY_PREFIX + journeyId + ":" + routeRevision + ":" + transferIndex;
    try {
        return Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(key, "1", properties.getTrigger().getCooldown()));
    } catch (Exception e) {
        log.error("[JourneyReroute] cooldown 확인 실패 — fail-closed [{}]: {}", key, e.getMessage());
        return false;
    }
}
```

### 2.3 신규 — 조율

**`application/journey/service/RerouteTriggerEvaluator.java`**

```java
/**
 * 방아쇠를 세고 기록한다. <b>여기서 끝난다</b> — 제안을 만들지 않는다(shadow).
 *
 * <p>{@code RerouteProposalService} 를 주입받지 않는 것이 그 계약을 구조로 보장한다. 켜는 것은
 * ③이 후보 공급자를 올릴 때다.
 */
public void evaluate(JourneyEvaluationContext context, TransferAssessmentBatch batch, Instant now);
```

흐름: `properties.getTrigger().isEnabled()` 확인 → `policy.signals(...)` → 신호마다
`metrics.recordTriggerFired(reason)` → `cooldownPort.tryAdmit(...)` 이 참이면
`metrics.recordTriggerAdmitted(reason)` + `log.info` 구조화 로그.

로그는 **admitted 일 때만** 남긴다. fired 마다 남기면 cooldown 이 깎는 만큼이 전부 로그가 되어
초당 200 평가에서 시끄럽다. 개수는 지표가 센다.

```java
log.info("[JourneyReroute] trigger journeyId={} rev={} transferIndex={} reason={} {}→{}",
        signal.journeyId(), signal.routeRevision(), signal.transferIndex(),
        signal.reason().tag(), signal.previousStatus(), signal.currentStatus());
```

### 2.4 수정 — 배선 한 줄

**`application/journey/service/JourneyEvaluationService.java`** — `evaluate()` 의
`if (!stored) return Report.lostClaim();` **다음**(현재 109행 부근):

```java
// 소유권을 확인한 뒤에만 방아쇠를 본다. 앞에 두면 lease 를 잃은 워커가 cooldown 을 태워
// 이긴 워커의 방아쇠를 먹는다(알림이 CAS 뒤에 오는 것과 같은 이유).
triggerEvaluator.evaluate(context, batch, now);
```

생성자에 `RerouteTriggerEvaluator` 를 더한다. 이 클래스는 테스트에서 손으로 생성되는 곳이 없다
(`grep 'new JourneyEvaluationService(' src/test` → 0건).

### 2.5 수정 — 계측

**`application/journey/observability/JourneyRerouteMetrics.java`** 에 둘 추가:

| 이름 | 태그 |
|---|---|
| `journey.reroute.trigger.fired` | `reason`(`degraded`·`target-passed`) |
| `journey.reroute.trigger.admitted` | `reason` |

**두 수의 차이가 cooldown 이 깎는 양이다.** `fired` 만 두면 ③이 켜졌을 때 외부 호출이 몇 번
나갈지 예측할 수 없고, `admitted` 만 두면 방아쇠 조건이 너무 좁은 건지 cooldown 이 먹는 건지
가를 수 없다.

식별자를 태그로 쓰지 않는다(REQUIREMENTS §6). `reason` 은 enum 이라 값 종류가 둘로 고정이다.

### 2.6 수정 — 설정

**`application/journey/config/JourneyRerouteProperties.java`** 에 중첩 `Trigger` 추가
(`Expiry` 와 같은 모양, `private final Trigger trigger = new Trigger();`):

| 키 | 기본값 | 근거 |
|---|---|---|
| `trigger.enabled` | **`true`** | shadow 는 값이 없으면 목적을 잃는다. 외부 호출도 제안도 없어 켜 둬도 안전하다. 끄는 스위치는 남긴다 |
| `trigger.decision-window-seconds` | `600` | PHASE-6 ① |
| `trigger.cooldown` | `PT2M` | PHASE-6 ① (120초) |

`@PostConstruct validate()` 에 한 줄 더한다 — window 와 cooldown 이 양수인지.

**`journey.reroute.enabled`(②의 기능 플래그, 기본 `false`)와 다른 스위치다.** 방아쇠는 기능이
꺼져 있는 동안 발생률을 재는 것이 일이므로 그 플래그를 보지 않는다. 이 구분을 주석에 적는다.

**`src/main/resources/application.yaml`** `journey.reroute` 블록에:

```yaml
    trigger:
      # shadow 다. 방아쇠가 켜져도 지표·로그만 남고 제안은 만들어지지 않는다.
      # 켜 두는 것이 기본값인 이유 - 발생률을 모른 채 ③을 켜면 요금이 먼저 온다.
      enabled: ${JOURNEY_REROUTE_TRIGGER_ENABLED:true}
      decision-window-seconds: 600
      cooldown: PT2M
```

## 3. 작업 순서

- [ ] **1. 브랜치** — `git switch -c feat/journey-reroute-trigger origin/main`
      (전환 전 커밋 안 된 변경은 무조건 stash)
- [ ] **2. 판정** — `RerouteTriggerSignal` → `RerouteTriggerPolicy`
- [ ] **3. 판정 단위 테스트** — `RerouteTriggerPolicyTest` (§4.1). **여기서 계약을 먼저 굳힌다**
- [ ] **4. cooldown** — `RerouteCooldownPort` → `RedisRerouteCooldownAdapter` → `RedisRerouteCooldownAdapterTest` (§4.3)
- [ ] **5. 설정** — `JourneyRerouteProperties.Trigger` + `application.yaml`
- [ ] **6. 계측** — `JourneyRerouteMetrics` 에 둘 추가
- [ ] **7. 조율** — `RerouteTriggerEvaluator` → `RerouteTriggerEvaluatorTest` (§4.2)
- [ ] **8. 배선** — `JourneyEvaluationService` 한 줄 → 평가기 회귀 확인
- [ ] **9. 전체 검증** — §5
- [ ] **10. 플랜 문서 커밋** — `docs/plan/journey-reroute-trigger/` 를 구현 PR 에 포함

의존성: 2→3, 4→7, 5→(4,6,7), 6→7, 7→8.

## 4. 테스트

### 4.1 `src/test/.../application/journey/service/RerouteTriggerPolicyTest.java`

순수 단위 테스트(컨테이너 불필요). **이 PR 의 계약 전부가 여기 있다.**

- **새 상태가 `UNKNOWN` 이면 어떤 전이도 방아쇠가 아니다** — `CATCHABLE(1) → UNKNOWN(2)` 은
  severity 가 오르지만 방아쇠가 아니다. **이 단언이 이 파일의 존재 이유다**: 실시간 API 가
  흔들릴 때마다 전 사용자에게 재탐색이 도는 것을 막는다
- `COMFORTABLE → AT_RISK` 는 방아쇠(`DEGRADED`), `AT_RISK → CATCHABLE` 은 아니다
- `AT_RISK → MISSED` 는 방아쇠(승격도 하락이다)
- `AT_RISK → AT_RISK`(변화 없음)는 아니다
- **문이 닫혀 있으면 어떤 하락도 방아쇠가 아니다** — `userReadyAt` 이 700초 뒤
- **`userReadyAt` 이 null 이면 문이 닫힌다**(GPS 끊김). `confidence == UNKNOWN` 도 마찬가지
- 통과 판정은 **직전** 판정의 `nextBusArrivalAt` 을 본다 — 이번 판정의 값(다음 차)이 아니다
- 이미 지나온 환승(`isUpcoming` false)은 제외
- 직전 판정이 없으면(첫 평가) 방아쇠 없음 — `null` 을 `UNKNOWN` 으로 취급하지 않는다
- ⓐ·ⓑ 동시 성립이면 `DEGRADED` 하나만 난다

### 4.2 `src/test/.../application/journey/service/RerouteTriggerEvaluatorTest.java`

- cooldown 이 막으면 `fired` 는 오르고 `admitted` 는 **안** 오른다
- cooldown 이 통과시키면 둘 다 오른다
- `trigger.enabled=false` 면 정책을 아예 부르지 않는다
- 신호가 없으면 cooldown 도 묻지 않는다 — 대부분의 평가가 여기 해당하므로 Redis 를 매번
  두드리면 안 된다
- **shadow 계약**: `RerouteTriggerEvaluator` 의 생성자에 `RerouteProposalService`·
  `RerouteProposalTxService` 가 없다. 구조로 보장되므로 시험은 "협력자 목록"을 단언하기보다
  이 사실을 주석으로 남긴다

### 4.3 `src/test/.../infrastructure/adapter/out/cache/journey/RedisRerouteCooldownAdapterTest.java`

`RedisBusApiQuotaTest` 골격(`@ExtendWith(MockitoExtension.class)` + `RedisTemplate` mock).

- 첫 호출은 통과(`setIfAbsent` → true), 두 번째는 거절
- TTL 이 설정값으로 걸린다
- **Redis 예외면 false**(fail-closed). 여기서 true 를 주면 ③에서 장애가 곧 요금이 된다

## 5. 검증

```bash
./gradlew compileJava compileTestJava

# 이 PR 의 테스트
./gradlew test --tests '*RerouteTrigger*'

# 평가기 회귀 (JourneyEvaluationService 생성자가 바뀐다. Docker 필요)
./gradlew test --tests 'watoo.grd.nextroute.application.journey.*'

# 전체
./gradlew test
```

통과 기준:

- 새 상태가 `UNKNOWN` 인 전이가 방아쇠를 만들지 않는다(§4.1 핵심 단언)
- `userReadyAt` 이 없으면 문이 닫힌다 — GPS 끊김에 별도 코드가 필요 없다는 근거
- cooldown 이 막은 만큼 `fired` 와 `admitted` 의 차이로 보인다
- **제안이 0건이다.** 이 PR 을 머지해도 `reroute_proposal` 에 행이 생기지 않는다
- 기존 평가기·알림 테스트 회귀 없음

## 6. 함정

- **`UNKNOWN` 의 severity 가 `CATCHABLE` 보다 크다**(`TransferStatus.severity()`). 숫자만
  비교하면 "모르겠다"가 방아쇠가 된다. 알릴 상태인지를 거르는 것이 숫자 비교보다 **먼저** 와야
  한다 — Phase 5 알림이 같은 함정을 밟았고 그 enum 주석에 경고가 적혀 있다
- **직전 상태를 `lastNotifiedStatus` 에서 읽지 말 것.** 알림이 꺼져 있으면 그 값이 비어 방아쇠가
  통째로 죽는다. 원본은 `TransferConnection.status`(= `previousAssessments`)다
- **`TransferAssessmentWriter.write` 가 직전 상태를 덮어쓴다.** 방아쇠 판정은 그 뒤에 돌지만
  `context.previousAssessments()` 는 평가 시작 시점에 읽어 둔 값이라 안전하다. 판정 안에서
  저장소를 다시 읽으면 **직전이 아니라 방금 쓴 값**을 보게 된다
- **소유권 확인 뒤에 부를 것**(§1.6). 앞에 두면 진 워커가 cooldown 을 태운다
- **트랜잭션 밖에서 부를 것.** Redis 호출을 쓰기 트랜잭션에 넣으면 DB 커넥션을 붙잡는다
- **`journey.reroute.enabled` 를 보지 말 것.** 그것은 ②의 기능 플래그(기본 off)이고, 방아쇠는
  기능이 꺼진 동안 발생률을 재는 것이 일이다. 두 스위치를 묶으면 이 PR 이 아무것도 측정하지 않는다
- **마이그레이션 없음.** 카운터가 필요한 판정(연속 N회 소실)을 이 PR 에 끌어들이면 그 전제가 깨진다

## 7. 뒤 PR 이 이 PR 에서 가져가는 것

| PR | 가져가는 것 |
|---|---|
| ③ route-options | `RerouteTriggerEvaluator` 가 신호를 `RerouteProposalService.request(...)` 로 넘기게 바꾼다. `RerouteEligibilityPolicy` 구현체를 올려 ②의 기본 거절 빈을 대체하고, 그 구현이 이 PR 의 `RerouteTriggerPolicy` + `RerouteCooldownPort` 를 재사용한다 |
| ③ | single-flight 를 외부 호출 자리에 붙인다. 키 `(journeyId, routeRevision, transferIndex, searchBucket)`, `application/route/concurrent/SingleFlight` 재사용 |
| ③ | 연속 N회 후보 소실 판정. 카운터의 집(Redis 키 또는 컬럼)을 그때 정한다 |
