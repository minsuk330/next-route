package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.journey.entity.JourneyEndReason;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.repository.JourneyNotificationRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.global.error.BusinessException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

class AdminJourneyNotificationTestServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");
    private static final long JOURNEY_ID = 42L;

    private JourneySessionRepository sessionRepository;
    private JourneyNotificationRepository notificationRepository;
    private AdminJourneyNotificationTestService service;

    @BeforeEach
    void setUp() {
        sessionRepository = org.mockito.Mockito.mock(JourneySessionRepository.class);
        notificationRepository = org.mockito.Mockito.mock(JourneyNotificationRepository.class);
        JourneyNotificationProperties properties = new JourneyNotificationProperties();
        service = new AdminJourneyNotificationTestService(
                sessionRepository, notificationRepository, properties,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void 활성Journey에_테스트_알림을_enqueue한다() {
        JourneySession journey = JourneySession.builder()
                .userId(7L)
                .startedAt(NOW.minusSeconds(60))
                .guidanceLeaseUntil(NOW.plusSeconds(180))
                .routeSnapshot(new ObjectMapper().createObjectNode())
                .build();
        given(sessionRepository.findById(JOURNEY_ID)).willReturn(Optional.of(journey));
        given(notificationRepository.enqueue(
                eq(JOURNEY_ID), eq(1), anyString(), anyInt(), anyString(), anyString(),
                anyString(), anyString(), eq(NOW), eq(NOW.plusSeconds(600)),
                anyString(), anyShort(), any())).willReturn(List.of(99L));

        AdminJourneyNotificationTestService.QueuedTestNotification result = service.enqueue(
                JOURNEY_ID, JourneyCheckpoint.TRANSFER_RISK_MISSED);

        assertThat(result.notificationId()).isEqualTo(99L);
        assertThat(result.journeyId()).isEqualTo(JOURNEY_ID);
        assertThat(result.checkpoint()).isEqualTo(JourneyCheckpoint.TRANSFER_RISK_MISSED);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).enqueue(
                eq(JOURNEY_ID), eq(1), eq("JOURNEY"), eq(0),
                eq("TRANSFER_RISK_MISSED"), eq("PUSH"),
                anyString(), anyString(), eq(NOW), eq(NOW.plusSeconds(600)),
                payload.capture(), eq((short) 2), any());
        assertThat(payload.getValue()).contains("\"adminTest\":true");
    }

    @Test
    void 종료된Journey는_테스트_알림을_enqueue하지_않는다() {
        JourneySession journey = JourneySession.builder()
                .userId(7L)
                .startedAt(NOW.minusSeconds(60))
                .guidanceLeaseUntil(NOW.plusSeconds(180))
                .routeSnapshot(new ObjectMapper().createObjectNode())
                .build();
        journey.end(JourneyStatus.COMPLETED, JourneyEndReason.COMPLETED, NOW);
        given(sessionRepository.findById(JOURNEY_ID)).willReturn(Optional.of(journey));

        assertThatThrownBy(() -> service.enqueue(
                JOURNEY_ID, JourneyCheckpoint.TRANSFER_RISK_AT_RISK))
                .isInstanceOf(BusinessException.class);
        org.mockito.Mockito.verifyNoInteractions(notificationRepository);
    }
}
