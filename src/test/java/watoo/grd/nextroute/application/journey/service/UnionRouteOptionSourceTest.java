package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.service.RerouteRouteOptionSource.SourceOutcome;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 저장값과 OTP 후보를 게이트에 따라 합치는 계약.
 *
 * <p>핵심: 게이트가 꺼져 있으면 <b>지금까지와 똑같이</b> 저장값만, 켜져 있으면 저장값을 <b>버리지
 * 않고</b> OTP 를 더한다(중복은 {@code (routeId, boardingSeq)} 로 뺀다). OTP 가 실패하면 저장값으로
 * 물러선다.
 */
@DisplayName("Stored ∪ OTP 대안 후보 출처")
class UnionRouteOptionSourceTest {

    private final StoredRouteOptionSource stored = mock(StoredRouteOptionSource.class);
    private final OtpPatternRouteOptionSource otp = mock(OtpPatternRouteOptionSource.class);
    private final JourneyRerouteProperties properties = new JourneyRerouteProperties();

    private UnionRouteOptionSource union;
    private JourneyLeg waitLeg;

    @BeforeEach
    void setUp() {
        union = new UnionRouteOptionSource(stored, otp, properties);
        waitLeg = waitLeg();
    }

    private SourceOutcome run() {
        return union.optionsFor(waitLeg, RoutingDeadline.of(Clock.systemUTC(), 3000));
    }

    @Test
    @DisplayName("게이트 꺼짐: 저장값만 돌려주고 OTP 는 부르지 않는다(PR-2 무행위변경)")
    void gateOffUsesStoredOnly() {
        properties.getRouteOption().setOtpPatternEnabled(false);
        when(stored.optionsFor(any(), any())).thenReturn(SourceOutcome.ok(List.of(option("R1", 5))));

        SourceOutcome outcome = run();

        assertThat(outcome.options()).extracting(JourneyLegRouteOption::getRouteId).containsExactly("R1");
        verify(otp, never()).optionsFor(any(), any());
    }

    @Test
    @DisplayName("게이트 켜짐: 저장값에 OTP 후보를 더한다")
    void gateOnUnionsBoth() {
        properties.getRouteOption().setOtpPatternEnabled(true);
        when(stored.optionsFor(any(), any())).thenReturn(SourceOutcome.ok(List.of(option("R1", 5))));
        when(otp.optionsFor(any(), any())).thenReturn(SourceOutcome.ok(List.of(option("R2", 8))));

        SourceOutcome outcome = run();

        assertThat(outcome.options()).extracting(JourneyLegRouteOption::getRouteId)
                .containsExactly("R1", "R2");
    }

    @Test
    @DisplayName("게이트 켜짐: 저장에 이미 있는 (routeId, boardingSeq) OTP 후보는 뺀다")
    void gateOnDedupesByRouteAndSeq() {
        properties.getRouteOption().setOtpPatternEnabled(true);
        when(stored.optionsFor(any(), any())).thenReturn(SourceOutcome.ok(List.of(option("R1", 5))));
        when(otp.optionsFor(any(), any())).thenReturn(SourceOutcome.ok(List.of(
                option("R1", 5),   // 저장과 동일 → 제외(기준선 재출력·중복 방지)
                option("R1", 9),   // 같은 노선이라도 다른 순번 → 남는다
                option("R2", 8))));

        SourceOutcome outcome = run();

        assertThat(outcome.options())
                .extracting(o -> o.getRouteId() + "|" + o.getBoardingSeq())
                .containsExactly("R1|5", "R1|9", "R2|8");
    }

    @Test
    @DisplayName("게이트 켜짐 + OTP 비정상(timeout/error/off): 저장값으로 물러선다")
    void gateOnFallsBackWhenOtpNotOk() {
        properties.getRouteOption().setOtpPatternEnabled(true);
        when(stored.optionsFor(any(), any())).thenReturn(SourceOutcome.ok(List.of(option("R1", 5))));
        when(otp.optionsFor(any(), any())).thenReturn(SourceOutcome.timeout());

        SourceOutcome outcome = run();

        assertThat(outcome.isOk()).isTrue();
        assertThat(outcome.options()).extracting(JourneyLegRouteOption::getRouteId).containsExactly("R1");
    }

    private JourneyLegRouteOption option(String routeId, int boardingSeq) {
        return JourneyLegRouteOption.builder()
                .journeyLegId(3L)
                .optionIndex(0)
                .laneIndex(0)
                .routeId(routeId)
                .boardingSeq(boardingSeq)
                .build();
    }

    private JourneyLeg waitLeg() {
        JourneyLeg leg = JourneyLeg.builder()
                .journeyId(42L)
                .routeRevision(1)
                .legIndex(2)
                .type(JourneyLegType.WAIT_BUS)
                .state(JourneyLegState.PENDING)
                .build();
        ReflectionTestUtils.setField(leg, "id", 3L);
        return leg;
    }
}
