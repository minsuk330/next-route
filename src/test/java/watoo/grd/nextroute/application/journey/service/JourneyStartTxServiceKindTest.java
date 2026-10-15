package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import watoo.grd.nextroute.application.journey.config.JourneyNavigationProperties;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.domain.journey.entity.JourneyKind;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyNotificationRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyRevisionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 여정 저장이 {@code kind} 를 싣고, 단독 추적이면 재탐색 보드 첫 예약을 건너뛴다(#265 PR-1).
 */
@DisplayName("여정 시작 저장 — kind")
class JourneyStartTxServiceKindTest {

    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");

    private final UserRepository userRepository = mock(UserRepository.class);
    private final JourneySessionRepository sessionRepository = mock(JourneySessionRepository.class);
    private final RerouteRefreshSignal rerouteRefreshSignal = mock(RerouteRefreshSignal.class);

    private JourneyStartTxService service;

    @BeforeEach
    void setUp() {
        User user = mock(User.class);
        given(user.getId()).willReturn(1L);
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(user));
        given(sessionRepository.findByUserIdAndStatusAndDeletedAtIsNull(anyLong(), any()))
                .willReturn(Optional.empty());
        given(sessionRepository.saveAndFlush(any(JourneySession.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        JourneyLegRepository legRepository = mock(JourneyLegRepository.class);
        given(legRepository.saveAllAndFlush(any())).willReturn(List.of());

        service = new JourneyStartTxService(new JourneyNavigationProperties(), userRepository,
                sessionRepository, mock(JourneyRevisionRepository.class), legRepository,
                mock(JourneyLegRouteOptionRepository.class), mock(TransferConnectionRepository.class),
                mock(JourneyNotificationRepository.class), mock(SubwayBindingLifecycleService.class),
                mock(JourneyQueryService.class), rerouteRefreshSignal,
                mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    @Test
    @DisplayName("kind 를 안 주는 기존 시작은 NAVIGATION 이고 보드 첫 예약을 심는다")
    void legacyStartIsNavigation() {
        service.start(1L, new JourneyPlan(List.of(), List.of()), json(), null, false, NOW);

        assertThat(savedSession().getKind()).isEqualTo(JourneyKind.NAVIGATION);
        verify(rerouteRefreshSignal).scheduleInitial(any());
    }

    @Test
    @DisplayName("TRACK 시작은 kind 를 싣고 보드 첫 예약을 심지 않는다")
    void trackStartSkipsBoard() {
        service.start(1L, new JourneyPlan(List.of(), List.of()), json(), null, false, NOW,
                JourneyKind.TRACK);

        assertThat(savedSession().getKind()).isEqualTo(JourneyKind.TRACK);
        verify(rerouteRefreshSignal, never()).scheduleInitial(any());
    }

    private JourneySession savedSession() {
        ArgumentCaptor<JourneySession> captor = ArgumentCaptor.forClass(JourneySession.class);
        verify(sessionRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private static com.fasterxml.jackson.databind.JsonNode json() {
        return new ObjectMapper().createObjectNode();
    }
}
