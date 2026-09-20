package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.RerouteTriggerSignal;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * "지금 재탐색을 찾아볼 만한가"를 판정한다. <b>아무것도 만들지 않는다.</b>
 *
 * <h2>문과 방아쇠는 다른 것이다</h2>
 * <b>문</b>(decision window)은 "이제 찾아봐도 되는 구간에 들어왔다"는 뜻일 뿐이고, 문이 열렸다고
 * 아무 일도 일어나지 않는다. 실제로 찾게 만드는 것은 <b>방아쇠</b>다.
 *
 * <p>둘을 섞으면 정상적으로 잘 가는 사용자마다 외부 검색이 나간다(SC-2 4항). 환승 정류장에
 * 가까워지는 것은 모든 여정에서 반드시 일어나는 일이라, 그것을 방아쇠로 쓰면 재탐색이 여정
 * 수만큼 돈다.
 *
 * <h2>모르는 것은 나빠진 것이 아니다</h2>
 * <b>새 상태가 {@link TransferStatus#UNKNOWN} 이면 절대 방아쇠가 아니다.</b> 이 클래스에서 가장
 * 중요한 규칙이다. 실시간 API 가 흔들리면 판정이 {@code UNKNOWN} 으로 떨어지는데,
 * {@link TransferStatus#severity()} 에서 {@code UNKNOWN}(2)이 {@code CATCHABLE}(1)보다 크기
 * 때문에 <b>숫자만 비교하면 "모르겠다"가 방아쇠가 된다.</b> 그러면 API 가 흔들릴 때마다 전
 * 사용자에게 재탐색이 돈다(X1 과 같은 원칙. Phase 5 알림이 같은 함정을 밟았다).
 *
 * <p>상태의 원본은 {@code TransferConnection.status} 다. {@code lastNotifiedStatus}(알림용
 * 흔적)를 쓰면 <b>알림이 꺼져 있을 때 방아쇠가 통째로 죽는다.</b>
 *
 * <p>외부 의존이 없고 상태도 없다. 이 PR 의 계약은 전부 이 클래스와 그 단위 테스트에 있다.
 */
@Component
@RequiredArgsConstructor
public class RerouteTriggerPolicy {

    /** 하락이 방아쇠가 되는 도착지. 이 필터가 순위 비교보다 <b>먼저</b> 와야 한다. */
    private static final Set<TransferStatus> TRIGGERING =
            EnumSet.of(TransferStatus.AT_RISK, TransferStatus.MISSED);

    private final JourneyRerouteProperties properties;

    /**
     * 이번 평가에서 재탐색을 찾아볼 만한 환승들.
     *
     * @param previous        직전 판정(환승 순번 → 판정). 평가 시작 시점에 읽어 둔 값이어야 한다 —
     *                        {@code TransferAssessmentWriter} 가 덮어쓴 뒤에 저장소를 다시 읽으면
     *                        직전이 아니라 방금 쓴 값을 보게 된다
     * @param current         이번 판정
     * @param currentLegIndex 지금 진행 중인 단계
     */
    public List<RerouteTriggerSignal> signals(Long journeyId, int routeRevision,
                                              Map<Integer, TransferRiskAssessment> previous,
                                              List<TransferRiskAssessment> current,
                                              int currentLegIndex, Instant now) {
        List<RerouteTriggerSignal> signals = new ArrayList<>();
        for (TransferRiskAssessment assessment : current) {
            if (!assessment.isUpcoming(currentLegIndex)) {
                // 이미 지나온 환승이다. 찾아 봐야 사용자가 할 수 있는 일이 없다.
                continue;
            }
            if (!windowOpen(assessment, now)) {
                continue;
            }
            TransferRiskAssessment before = previous == null
                    ? null : previous.get(assessment.transferIndex());
            reasonFor(before, assessment, now).ifPresent(reason -> signals.add(
                    new RerouteTriggerSignal(journeyId, routeRevision, assessment.transferIndex(),
                            reason, before == null ? null : before.status(), assessment.status(),
                            assessment.userReadyAt())));
        }
        return signals;
    }

    /**
     * 이제 찾아봐도 되는 구간인가.
     *
     * <p>기준은 <b>{@code userReadyAt} 하나</b>다. PHASE-6 문서는 "또는 {@code remainingStops}
     * ≤ 2"를 함께 적었지만 뺐다 — {@code userReadyAt = currentLegArrivalAt + 하차버퍼 + 환승도보}
     * 이므로 언제나 도착 시각 뒤이고, 두 정류장 남은 버스는 그 안에 들어온다. 두 번째 다리가 여는
     * 문은 첫 번째가 이미 연 문이다. 게다가 {@code remainingStops} 는 {@code JourneyLeg} 에 있어
     * 평가마다 조회가 하나 는다.
     *
     * <p><b>근거가 없으면 열지 않는다.</b> GPS 가 끊기면 진행도가 사라져 {@code userReadyAt} 이
     * null 이 되고 {@code confidence} 가 {@code UNKNOWN} 으로 떨어진다 — 문이 저절로 닫힌다.
     * 낡은 값으로 문을 열면 엉뚱한 때에 외부 검색이 나간다.
     */
    private boolean windowOpen(TransferRiskAssessment assessment, Instant now) {
        if (assessment.userReadyAt() == null
                || assessment.status() == TransferStatus.UNKNOWN
                || assessment.confidence() == CurrentLegArrival.Quality.UNKNOWN) {
            return false;
        }
        return withinWindow(assessment.userReadyAt(), now);
    }

    /**
     * 환승 준비 시각이 문 안에 들어왔는가. <b>임계값을 아는 유일한 자리다.</b>
     *
     * <p>사용자 요청 경로({@code WindowCooldownEligibilityPolicy})가 같은 판정을 해야 하는데,
     * 거기서 임계값을 다시 읽으면 두 경로가 서로 다른 문을 갖게 된다 — 주기 평가는 열렸다고
     * 세는데 버튼은 거절하는 상태가 조용히 생긴다. 그래서 판정을 여기 한 곳에 둔다.
     *
     * <p><b>근거가 없는 경우(userReadyAt == null)는 부르는 쪽이 먼저 거른다.</b> 여기는 시각
     * 비교만 한다 — 근거 유무의 판정은 부르는 쪽이 어떤 사유로 거절할지와 묶여 있다.
     */
    public boolean withinWindow(Instant userReadyAt, Instant now) {
        Duration untilReady = Duration.between(now, userReadyAt);
        return untilReady.compareTo(properties.getTrigger().decisionWindow()) <= 0;
    }

    /**
     * 찾아볼 만한 사건이 있었는가.
     *
     * <p>둘 다 성립하면 {@code DEGRADED} 하나만 낸다. 같은 환승에서 신호를 둘 내면 cooldown 이
     * 하나를 삼켜 지표가 사유별로 어긋난다 — 하락이 더 구체적인 사건이라 그쪽을 남긴다.
     */
    private Optional<RerouteTriggerSignal.Reason> reasonFor(
            TransferRiskAssessment before, TransferRiskAssessment current, Instant now) {
        if (before == null) {
            // 여정의 첫 평가다. 비교할 직전이 없으면 "나빠졌다"도 "차를 놓쳤다"도 성립하지 않는다.
            // null 을 UNKNOWN 으로 취급하면 UNKNOWN(2) → AT_RISK(3) 로 읽혀 모든 여정이
            // 시작하자마자 한 번씩 방아쇠를 켠다.
            return Optional.empty();
        }
        if (degraded(before, current)) {
            return Optional.of(RerouteTriggerSignal.Reason.DEGRADED);
        }
        if (targetPassed(before, current, now)) {
            return Optional.of(RerouteTriggerSignal.Reason.TARGET_PASSED);
        }
        return Optional.empty();
    }

    /**
     * 상태가 나빠졌는가.
     *
     * <p>도착지를 먼저 거른다({@link #TRIGGERING}). 순위 비교를 먼저 하면 {@code UNKNOWN} 이
     * {@code CATCHABLE} 보다 커서 "모르겠다"가 방아쇠가 된다.
     */
    private boolean degraded(TransferRiskAssessment before, TransferRiskAssessment current) {
        return TRIGGERING.contains(current.status())
                && current.status().severity() > before.status().severity();
    }

    /**
     * 타려던 차가 이미 지나갔는가.
     *
     * <p>보는 것은 <b>직전 판정의</b> {@code nextBusArrivalAt} 이다. 이번 판정의 값은 다시 계산한
     * 다음 차라서, 그것을 보면 "타려던 차"가 아니라 "새로 잡힌 차"를 두고 판정하게 된다.
     *
     * <p>사용자가 이미 탔으면 {@code currentLegIndex} 가 그 대기 단계를 지나가므로 부르는 쪽의
     * {@code isUpcoming} 이 걸러 낸다.
     *
     * <p><b>이미 편해진 상황은 제외한다.</b> 타려던 차가 지나갔어도 다음 차로 여유 있게 갈 수
     * 있으면({@code COMFORTABLE}) 찾아 봐야 나은 것이 없다 — 최소 개선폭(300초)을 넘길 리가
     * 없어 <b>아무것도 못 내놓고 외부 검색 요금만 쓴다.</b> 이 Phase 의 축이 "외부 검색을 안 부르는
     * 것"이므로 여기서 거른다. {@code CATCHABLE} 은 남긴다 — 서두르면 되는 상황은 사용자가 다른
     * 길을 볼 값이 있다.
     */
    private boolean targetPassed(TransferRiskAssessment before, TransferRiskAssessment current,
                                 Instant now) {
        return before.status() != TransferStatus.UNKNOWN
                && current.status() != TransferStatus.COMFORTABLE
                && before.nextBusArrivalAt() != null
                && before.nextBusArrivalAt().isBefore(now);
    }
}
