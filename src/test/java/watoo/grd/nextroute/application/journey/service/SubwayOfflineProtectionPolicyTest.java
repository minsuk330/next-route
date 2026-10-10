package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneyNavigationProperties;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보호 시각 계산 규칙. 두 값의 최소값을 고르는 것이 전부라 여기서 경계만 못박는다.
 *
 * <pre>
 * 보호 시각 = min(예상 종료 + 5분, 단계 시작 + 60분)
 * </pre>
 */
@DisplayName("지하철 오프라인 보호 시각")
class SubwayOfflineProtectionPolicyTest {

    private static final Instant LEG_STARTED_AT = Instant.parse("2026-08-04T09:00:00Z");

    private final JourneyNavigationProperties properties = new JourneyNavigationProperties();
    private final SubwayOfflineProtectionPolicy policy =
            new SubwayOfflineProtectionPolicy(properties);

    @Test
    @DisplayName("관측이 없으면 계획 소요시간에 5분을 더한다")
    void fallsBackToPlannedDuration() {
        JourneySession journey = journey();

        policy.apply(journey, subwayLeg(900), null, LEG_STARTED_AT);

        // 09:00 시작 + 계획 15분 + 여유 5분
        assertThat(journey.getSubwayOfflineProtectionUntil())
                .isEqualTo(Instant.parse("2026-08-04T09:20:00Z"));
    }

    @Test
    @DisplayName("관측으로 보정된 종료 시각이 있으면 그것을 쓴다")
    void usesObservedEnd() {
        JourneySession journey = journey();

        policy.apply(journey, subwayLeg(900),
                Instant.parse("2026-08-04T09:30:00Z"), LEG_STARTED_AT);

        assertThat(journey.getSubwayOfflineProtectionUntil())
                .isEqualTo(Instant.parse("2026-08-04T09:35:00Z"));
    }

    @Test
    @DisplayName("단계 시작 + 60분을 넘기지 않는다 — 시간이 흐른다고 무한히 늘지 않는다")
    void capsAtOneHourFromLegStart() {
        JourneySession journey = journey();

        policy.apply(journey, subwayLeg(900),
                Instant.parse("2026-08-04T11:00:00Z"), LEG_STARTED_AT);

        assertThat(journey.getSubwayOfflineProtectionUntil())
                .isEqualTo(Instant.parse("2026-08-04T10:00:00Z"));
    }

    @Test
    @DisplayName("지하철이 아닌 단계로 옮기면 보호를 지운다")
    void clearsOnNonSubwayLeg() {
        JourneySession journey = journey();
        journey.protectSubwayUntil(Instant.parse("2026-08-04T09:20:00Z"));

        policy.apply(journey, rideBusLeg(), null, LEG_STARTED_AT);

        assertThat(journey.getSubwayOfflineProtectionUntil()).isNull();
    }

    @Test
    @DisplayName("현재 단계를 모르면 보호할 근거가 없다")
    void clearsWhenLegUnknown() {
        JourneySession journey = journey();
        journey.protectSubwayUntil(Instant.parse("2026-08-04T09:20:00Z"));

        policy.apply(journey, null, null, LEG_STARTED_AT);

        assertThat(journey.getSubwayOfflineProtectionUntil()).isNull();
    }

    @Test
    @DisplayName("단계 시작 시각이 아직 없으면 지금 시각을 기준으로 삼는다")
    void usesNowWhenLegNotActivatedYet() {
        JourneySession journey = journey();
        JourneyLeg notActivated = JourneyLeg.builder()
                .journeyId(1L).routeRevision(1).legIndex(1)
                .type(JourneyLegType.RIDE_SUBWAY).state(JourneyLegState.ACTIVE)
                .plannedDurationSec(600)
                .build();

        policy.apply(journey, notActivated, null, LEG_STARTED_AT);

        // 09:00 + 계획 10분 + 여유 5분
        assertThat(journey.getSubwayOfflineProtectionUntil())
                .isEqualTo(Instant.parse("2026-08-04T09:15:00Z"));
    }

    @Test
    @DisplayName("계획 소요시간조차 없으면 여유 5분만 준다")
    void protectsAtLeastTheBuffer() {
        JourneySession journey = journey();

        policy.apply(journey, subwayLeg(null), null, LEG_STARTED_AT);

        assertThat(journey.getSubwayOfflineProtectionUntil())
                .isEqualTo(Instant.parse("2026-08-04T09:05:00Z"));
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private JourneySession journey() {
        return JourneySession.builder()
                .userId(1L)
                .startedAt(LEG_STARTED_AT)
                .guidanceLeaseUntil(LEG_STARTED_AT.plusSeconds(180))
                .build();
    }

    private JourneyLeg subwayLeg(Integer plannedDurationSec) {
        return JourneyLeg.builder()
                .journeyId(1L).routeRevision(1).legIndex(1)
                .type(JourneyLegType.RIDE_SUBWAY).state(JourneyLegState.ACTIVE)
                .plannedDurationSec(plannedDurationSec)
                .activatedAt(LEG_STARTED_AT)
                .build();
    }

    private JourneyLeg rideBusLeg() {
        return JourneyLeg.builder()
                .journeyId(1L).routeRevision(1).legIndex(2)
                .type(JourneyLegType.RIDE_BUS).state(JourneyLegState.ACTIVE)
                .plannedDurationSec(600)
                .activatedAt(LEG_STARTED_AT)
                .build();
    }

    @Test
    @DisplayName("같은 탑승 안에서는 줄이지 않는다 — 링 갱신기가 하차 예정으로 늘린 값을 계획 소요시간이 되돌리지 않는다")
    void neverShrinksWithinTheRide() {
        JourneySession journey = journey();
        journey.protectSubwayUntil(Instant.parse("2026-08-04T09:40:00Z"));

        policy.apply(journey, subwayLeg(900), null, LEG_STARTED_AT);

        assertThat(journey.getSubwayOfflineProtectionUntil())
                .isEqualTo(Instant.parse("2026-08-04T09:40:00Z"));
    }

    @Test
    @DisplayName("줄이지 않아도 상한(단계 시작 + 60분)은 넘지 않는다")
    void keptValueStillCapped() {
        JourneySession journey = journey();
        journey.protectSubwayUntil(Instant.parse("2026-08-04T11:00:00Z"));

        policy.apply(journey, subwayLeg(900), null, LEG_STARTED_AT);

        assertThat(journey.getSubwayOfflineProtectionUntil())
                .isEqualTo(Instant.parse("2026-08-04T10:00:00Z"));
    }

}
