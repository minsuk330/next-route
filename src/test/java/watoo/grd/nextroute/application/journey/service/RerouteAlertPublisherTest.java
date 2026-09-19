package watoo.grd.nextroute.application.journey.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateApply;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateDisplay;
import watoo.grd.nextroute.application.journey.dto.RerouteContext;
import watoo.grd.nextroute.application.journey.dto.RerouteOrigin;
import watoo.grd.nextroute.application.journey.dto.RerouteOriginKind;
import watoo.grd.nextroute.application.journey.dto.RerouteProposalView;
import watoo.grd.nextroute.application.journey.dto.RerouteTriggerSignal;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.application.journey.port.out.NotificationChannelResolver;
import watoo.grd.nextroute.application.journey.port.out.RerouteCooldownPort;
import watoo.grd.nextroute.application.journey.service.RerouteProposalService.Proposed;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 서버가 먼저 알리는 경로.
 *
 * <p>못박는 것은 <b>알리지 않을 이유</b>들이다 — 알림은 사용자의 주머니를 울리는 유일한 통로라,
 * 종료·상한·cooldown·문턱·리드타임·채널 중 어느 하나라도 새면 그 피로가 그대로 사용자에게 간다.
 * 그리고 그 판단이 <b>후보를 찾기 전</b>에 끝나야 한다: 어차피 안 보낼 알림 때문에 OTP·실시간·ML
 * 호출이 나가면 안 된다.
 */
@DisplayName("재탐색 알림 발행")
class RerouteAlertPublisherTest {

    private static final Long JOURNEY_ID = 7L;
    private static final Long USER_ID = 3L;
    private static final Instant NOW = Instant.parse("2026-09-19T09:00:00Z");

    private final RerouteProposalService proposalService = mock(RerouteProposalService.class);
    private final RerouteAlertTxService alertTxService = mock(RerouteAlertTxService.class);
    private final JourneySessionRepository sessionRepository = mock(JourneySessionRepository.class);
    private final NotificationChannelResolver channelResolver = mock(NotificationChannelResolver.class);
    private final JourneyRelayWaker relayWaker = mock(JourneyRelayWaker.class);
    private final JourneyRerouteProperties properties = new JourneyRerouteProperties();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final JourneyRerouteMetrics metrics = new JourneyRerouteMetrics(registry);

    private RerouteCooldownPort cooldownPort = (journeyId, revision, scope) -> true;
    private JourneySession journey;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.getAlert().setEnabled(true);
        journey = journey();
        given(sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(JOURNEY_ID, USER_ID))
                .willReturn(Optional.of(journey));
        given(channelResolver.resolve(USER_ID)).willReturn(List.of(NotificationChannel.PUSH));
        given(proposalService.collect(any())).willReturn(Optional.of(proposed(600, NOW.plusSeconds(400))));
        given(proposalService.store(any(), any(), any())).willReturn(Optional.of(view()));
        given(alertTxService.alert(any(), anyInt(), any(), any())).willReturn(true);
    }

    @Test
    @DisplayName("조건을 다 지나면 제안을 저장하고 알림을 쌓은 뒤 relay 를 깨운다")
    void publishesAndWakesRelay() {
        publisher().publish(signal(), USER_ID, NOW);

        verify(proposalService).store(any(RerouteContext.class), any(Proposed.class),
                eq(NOW.plus(properties.getAlert().getSnapshotTtl())));
        verify(alertTxService).alert(any(), eq(600), eq(List.of(NotificationChannel.PUSH)), eq(NOW));
        verify(relayWaker).wake();
        assertThat(alertCount("published")).isEqualTo(1);
    }

    @Test
    @DisplayName("알림 스위치가 꺼져 있으면 후보조차 찾지 않는다 — shadow 그대로다")
    void disabledAlertsDoNothing() {
        properties.getAlert().setEnabled(false);

        publisher().publish(signal(), USER_ID, NOW);

        verifyNoInteractions(proposalService);
        assertThat(alertCount("disabled")).isEqualTo(1);
    }

    @Test
    @DisplayName("수락·거절로 끝난 여정에는 알리지 않는다")
    void terminatedJourneyIsSilent() {
        journey.terminateReroute();

        publisher().publish(signal(), USER_ID, NOW);

        verifyNoInteractions(proposalService);
        assertThat(alertCount("terminated")).isEqualTo(1);
    }

    @Test
    @DisplayName("무응답 상한을 넘으면 알리지 않는다")
    void ignoreLimitStopsAlerts() {
        journey.recordRerouteIgnored();
        journey.recordRerouteIgnored();

        publisher().publish(signal(), USER_ID, NOW);

        verifyNoInteractions(proposalService);
        assertThat(alertCount("ignore-limit")).isEqualTo(1);
    }

    @Test
    @DisplayName("계속 받기를 켠 여정은 상한을 넘겨도 알린다")
    void optInIgnoresTheLimit() {
        journey.recordRerouteIgnored();
        journey.recordRerouteIgnored();
        journey.optInRerouteAlert();

        publisher().publish(signal(), USER_ID, NOW);

        verify(relayWaker).wake();
    }

    @Test
    @DisplayName("cooldown 에 걸리면 후보를 찾지 않는다 — 안 보낼 알림에 외부 호출을 쓰지 않는다")
    void cooldownStopsBeforeSearching() {
        cooldownPort = (journeyId, revision, scope) -> false;

        publisher().publish(signal(), USER_ID, NOW);

        verifyNoInteractions(proposalService);
        assertThat(alertCount("cooldown")).isEqualTo(1);
    }

    @Test
    @DisplayName("개선폭이 문턱 미만이면 저장하지 않는다")
    void smallGainIsNotWorthWaking() {
        given(proposalService.collect(any()))
                .willReturn(Optional.of(proposed(120, NOW.plusSeconds(400))));

        publisher().publish(signal(), USER_ID, NOW);

        verify(proposalService, never()).store(any(), any(), any());
        assertThat(alertCount("below-threshold")).isEqualTo(1);
    }

    @Test
    @DisplayName("하차점이 이미 코앞이면 알리지 않는다 — 읽고 내릴 시간이 없다")
    void withoutLeadTimeNothingIsSent() {
        given(proposalService.collect(any()))
                .willReturn(Optional.of(proposed(600, NOW.plusSeconds(30))));

        publisher().publish(signal(), USER_ID, NOW);

        verify(proposalService, never()).store(any(), any(), any());
        assertThat(alertCount("no-lead-time")).isEqualTo(1);
    }

    @Test
    @DisplayName("보낼 채널이 없으면 제안도 만들지 않는다 — 아무도 못 볼 제안이 자리를 막는다")
    void withoutChannelNothingIsStored() {
        given(channelResolver.resolve(USER_ID)).willReturn(List.of());

        publisher().publish(signal(), USER_ID, NOW);

        verify(proposalService, never()).store(any(), any(), any());
        assertThat(alertCount("no-channel")).isEqualTo(1);
    }

    @Test
    @DisplayName("중복이라 알림 행이 안 생기면 relay 를 깨우지 않는다")
    void duplicateDoesNotWake() {
        given(alertTxService.alert(any(), anyInt(), any(), any())).willReturn(false);

        publisher().publish(signal(), USER_ID, NOW);

        verify(relayWaker, never()).wake();
        assertThat(alertCount("duplicate")).isEqualTo(1);
    }

    @Test
    @DisplayName("발행이 터져도 예외를 올리지 않는다 — 이미 커밋된 평가가 실패로 기록된다")
    void failureIsSwallowed() {
        given(proposalService.collect(any())).willThrow(new IllegalStateException("OTP down"));

        publisher().publish(signal(), USER_ID, NOW);

        assertThat(alertCount("failed")).isEqualTo(1);
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private RerouteAlertPublisher publisher() {
        return new RerouteAlertPublisher(proposalService, alertTxService, sessionRepository,
                cooldownPort, channelResolver, relayWaker, properties, metrics);
    }

    private double alertCount(String outcome) {
        return registry.find(JourneyRerouteMetrics.ALERT).tag("outcome", outcome).counter().count();
    }

    private RerouteTriggerSignal signal() {
        return new RerouteTriggerSignal(JOURNEY_ID, 1, 0,
                RerouteTriggerSignal.Reason.DEGRADED, TransferStatus.COMFORTABLE,
                TransferStatus.AT_RISK, NOW.plusSeconds(300));
    }

    private Proposed proposed(int gainSec, Instant alightAt) {
        return new Proposed(RerouteProposalKind.REMAINING_PATH, List.of(candidate(gainSec, alightAt)));
    }

    private RerouteCandidate candidate(int gainSec, Instant alightAt) {
        return new RerouteCandidate("c-1", RerouteProposalKind.REMAINING_PATH,
                new RerouteCandidateApply.RemainingPathApply(2, null),
                new RerouteCandidateDisplay(NOW.plusSeconds(1_800), 1_800, gainSec, 1, 300,
                        "REALTIME", "GOOD", NOW, List.of("370 버스"),
                        new RerouteCandidateDisplay.AlightStop("ST-B", "정류장B", alightAt, false)));
    }

    private RerouteProposalView view() {
        return new RerouteProposalView(11L, JOURNEY_ID, 1,
                new RerouteOrigin(2, "ST-C", "BL-360", 4, RerouteOriginKind.TRANSFER_WAIT),
                RerouteProposalKind.REMAINING_PATH, 1L, NOW,
                NOW.plus(properties.getAlert().getSnapshotTtl()), List.of());
    }

    private JourneySession journey() {
        JourneySession session = JourneySession.builder()
                .userId(USER_ID)
                .startedAt(NOW)
                .guidanceLeaseUntil(NOW.plusSeconds(180))
                .routeSnapshot(null)
                .build();
        ReflectionTestUtils.setField(session, "id", JOURNEY_ID);
        return session;
    }
}
