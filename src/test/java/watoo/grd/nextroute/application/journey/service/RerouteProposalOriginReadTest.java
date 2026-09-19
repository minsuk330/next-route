package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.RerouteOriginKind;
import watoo.grd.nextroute.application.journey.dto.RerouteProposalView;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposal;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.RerouteProposalRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PR-B stage3 이중 읽기: origin 이 있으면 그것을, 없거나 못 읽으면 null 을 준다(부르는 쪽이 옛
 * {@code transferIndex} 로 폴백). 전환 기간에 origin 없는 행이 섞여 있는 것이 정상이다.
 */
class RerouteProposalOriginReadTest {

    private static final long JOURNEY_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-09-18T09:00:00Z");
    private static final String EMPTY_CANDIDATES = "{\"version\":1,\"candidates\":[]}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RerouteProposalRepository proposalRepository = mock(RerouteProposalRepository.class);
    private final RerouteProposalTxService service = new RerouteProposalTxService(
            proposalRepository,
            mock(JourneySessionRepository.class),
            mock(TransferConnectionRepository.class),
            mock(RerouteOriginResolver.class),
            mock(JourneyRerouteProperties.class),
            mock(JourneyRerouteMetrics.class),
            objectMapper,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private void storedProposal(String originJson) throws Exception {
        JsonNode origin = originJson == null ? null : objectMapper.readTree(originJson);
        RerouteProposal proposal = RerouteProposal.builder()
                .journeyId(JOURNEY_ID).userId(1L).routeRevision(1)
                .origin(origin)
                .kind(RerouteProposalKind.REMAINING_PATH)
                .baseCommandVersion(1L)
                .candidates(objectMapper.readTree(EMPTY_CANDIDATES))
                .snapshotObservedAt(NOW).expiresAt(NOW.plusSeconds(120))
                .build();
        when(proposalRepository.findFirstByJourneyIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
                anyLong(), any(RerouteProposalStatus.class)))
                .thenReturn(Optional.of(proposal));
    }

    @Test
    @DisplayName("저장된 origin 은 서울 좌표계 그대로 왕복한다")
    void readsStoredOrigin() throws Exception {
        storedProposal("""
                {"version":1,"origin":{"legIndex":4,"stopId":"STOP-A","routeId":"ROUTE-1",
                 "seq":7,"kind":"TRANSFER_WAIT"}}""");

        RerouteProposalView view = service.findActive(JOURNEY_ID).orElseThrow();

        assertThat(view.origin()).isNotNull();
        assertThat(view.origin().legIndex()).isEqualTo(4);
        assertThat(view.origin().stopId()).isEqualTo("STOP-A");
        assertThat(view.origin().routeId()).isEqualTo("ROUTE-1");
        assertThat(view.origin().seq()).isEqualTo(7);
        assertThat(view.origin().kind()).isEqualTo(RerouteOriginKind.TRANSFER_WAIT);
        assertThat(view.origin().isRealtimeAddressable()).isTrue();
    }

    @Test
    @DisplayName("origin 이 없는 옛 행은 origin=null — transferIndex 가 그대로 주소다(폴백)")
    void legacyRowFallsBackToTransferIndex() throws Exception {
        storedProposal(null);

        RerouteProposalView view = service.findActive(JOURNEY_ID).orElseThrow();

        // 옛 행(주소 없음)은 읽히기는 한다. 새로 저장하는 행에는 원점이 반드시 있다(stage5 2/2).
        assertThat(view.origin()).isNull();
    }

    @Test
    @DisplayName("모르는 origin 계약 판은 없는 것으로 친다 — 새 뜻으로 읽어 엉뚱한 정류장을 가리키지 않는다")
    void unknownOriginVersionIsIgnored() throws Exception {
        storedProposal("""
                {"version":99,"origin":{"legIndex":4,"stopId":"STOP-A","routeId":"ROUTE-1",
                 "seq":7,"kind":"TRANSFER_WAIT"}}""");

        RerouteProposalView view = service.findActive(JOURNEY_ID).orElseThrow();

        assertThat(view.origin()).isNull();
    }
}
