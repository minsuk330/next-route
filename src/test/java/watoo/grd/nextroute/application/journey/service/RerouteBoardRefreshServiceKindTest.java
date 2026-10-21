package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.domain.journey.entity.JourneyKind;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 사용자 요청 제안(보드 모드)은 선점 없이 {@code refresh} 를 직접 부른다. 선점 SQL 이 TRACK 을 걸러도
 * 이 길은 지나가므로 여기서 한 번 더 막는다(#265).
 */
@DisplayName("보드 직접 갱신 — 단독 추적 제외")
class RerouteBoardRefreshServiceKindTest {

    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");

    private final JourneySessionRepository sessionRepository = mock(JourneySessionRepository.class);
    private final RemainingPathCandidateProvider candidateProvider = mock(RemainingPathCandidateProvider.class);
    private final RerouteBoardService boardService = mock(RerouteBoardService.class);
    private final RerouteBoardPushService pushService = mock(RerouteBoardPushService.class);

    private final RerouteBoardRefreshService service = new RerouteBoardRefreshService(sessionRepository,
            candidateProvider, boardService, pushService, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("TRACK 여정이면 후보를 찾지도, 보드를 쓰지도 않는다")
    void trackIsSkipped() {
        given(sessionRepository.findById(7L)).willReturn(Optional.of(session(JourneyKind.TRACK)));

        assertThat(service.refresh(7L)).isEmpty();

        verify(candidateProvider, never()).evaluateBoard(any());
        verify(boardService, never()).write(any(), any());
        verify(pushService, never()).considerPush(any(), any(), any());
    }

    @Test
    @DisplayName("보드 회차는 여정 세션의 급행 끄기를 후보 공급자에 넘긴다(subway-express S5)")
    void passesSessionExcludeExpress() {
        given(sessionRepository.findById(7L)).willReturn(Optional.of(session(JourneyKind.NAVIGATION, true)));
        given(candidateProvider.evaluateBoard(any())).willReturn(
                new RemainingPathCandidateProvider.BoardResult(java.util.Set.of(), java.util.List.of(), true));
        given(boardService.write(any(), any())).willReturn(new RerouteBoardService.Written(
                RerouteBoardService.Outcome.STALE_CONTEXT, java.util.List.of(), java.util.Map.of()));

        service.refresh(7L);

        org.mockito.ArgumentCaptor<RemainingPathCandidateProvider.BoardRequest> request =
                org.mockito.ArgumentCaptor.forClass(RemainingPathCandidateProvider.BoardRequest.class);
        verify(candidateProvider).evaluateBoard(request.capture());
        assertThat(request.getValue().excludeExpress()).isTrue();
    }

    private static JourneySession session(JourneyKind kind) {
        return session(kind, false);
    }

    private static JourneySession session(JourneyKind kind, boolean excludeExpress) {
        return JourneySession.builder()
                .userId(1L)
                .kind(kind)
                .excludeExpress(excludeExpress)
                .startedAt(NOW)
                .guidanceLeaseUntil(NOW.plusSeconds(180))
                .routeSnapshot(new ObjectMapper().createObjectNode())
                .build();
    }
}
