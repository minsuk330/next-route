package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.RerouteOrigin;
import watoo.grd.nextroute.application.journey.dto.RerouteOriginKind;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** PR-B stage3: 옛 transferIndex → 서울 좌표계 원점. 못 풀면 빈 결과(예외 아님), 애매하면 seq 를 비운다. */
class RerouteOriginResolverTest {

    private static final long JOURNEY_ID = 7L;
    private static final int REVISION = 3;
    private static final int TRANSFER_INDEX = 1;
    private static final int BOARDING_LEG = 4;

    private final TransferConnectionRepository transferRepository = mock(TransferConnectionRepository.class);
    private final JourneyLegRepository legRepository = mock(JourneyLegRepository.class);
    private final BusRouteStopRepository busRouteStopRepository = mock(BusRouteStopRepository.class);
    private final RerouteOriginResolver resolver =
            new RerouteOriginResolver(transferRepository, legRepository, busRouteStopRepository);

    private void transferExists() {
        when(transferRepository.findByJourneyIdAndRouteRevisionAndTransferIndexAndDeletedAtIsNull(
                anyLong(), anyInt(), anyInt()))
                .thenReturn(Optional.of(TransferConnection.builder()
                        .journeyId(JOURNEY_ID).routeRevision(REVISION).transferIndex(TRANSFER_INDEX)
                        .fromLegIndex(2).walkLegIndex(3).toLegIndex(BOARDING_LEG)
                        .status(TransferStatus.UNKNOWN)
                        .build()));
    }

    private void boardingLeg(String startStopId, String routeId) {
        JourneyLeg leg = JourneyLeg.builder()
                .journeyId(JOURNEY_ID).routeRevision(REVISION).legIndex(BOARDING_LEG)
                .type(JourneyLegType.RIDE_BUS).state(JourneyLegState.PENDING)
                .startStopId(startStopId).endStopId("STOP-END")
                .build();
        if (routeId != null) {
            leg.selectRouteOption(11L, routeId);   // routeId 는 노선 확정으로만 채워진다
        }
        when(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                anyLong(), anyInt()))
                .thenReturn(List.of(leg));
    }

    private void routeStopSeq(Integer... seqs) {
        List<BusRouteStop> rows = java.util.Arrays.stream(seqs).map(seq -> {
            BusRouteStop row = mock(BusRouteStop.class);
            when(row.getSeq()).thenReturn(seq);
            return row;
        }).toList();
        when(busRouteStopRepository.findByRouteIdAndStopId(anyString(), anyString())).thenReturn(rows);
    }

    @Test
    @DisplayName("환승 원점 = 탑승 leg 의 승차 정류장, 서울 좌표계(stopId/routeId/seq)로 채운다")
    void resolvesBoardingStopAsOrigin() {
        transferExists();
        boardingLeg("STOP-A", "ROUTE-1");
        routeStopSeq(7);

        RerouteOrigin origin = resolver
                .resolveTransferWait(JOURNEY_ID, REVISION, TRANSFER_INDEX).orElseThrow();

        assertThat(origin.legIndex()).isEqualTo(BOARDING_LEG);
        assertThat(origin.stopId()).isEqualTo("STOP-A");
        assertThat(origin.routeId()).isEqualTo("ROUTE-1");
        assertThat(origin.seq()).isEqualTo(7);
        assertThat(origin.kind()).isEqualTo(RerouteOriginKind.TRANSFER_WAIT);
        assertThat(origin.isRealtimeAddressable()).isTrue();
    }

    @Test
    @DisplayName("같은 정류장이 노선에 두 번 나오면(순환) seq 를 지어내지 않고 비운다 → 실시간 후보 아님")
    void ambiguousSeqStaysEmpty() {
        transferExists();
        boardingLeg("STOP-A", "ROUTE-LOOP");
        routeStopSeq(5, 21);

        RerouteOrigin origin = resolver
                .resolveTransferWait(JOURNEY_ID, REVISION, TRANSFER_INDEX).orElseThrow();

        assertThat(origin.seq()).isNull();
        assertThat(origin.isRealtimeAddressable()).isFalse();
    }

    @Test
    @DisplayName("노선이 없으면(지하철·미확정) seq 없이 원점만 만든다 → 실시간 후보 아님")
    void noRouteMeansNoSeq() {
        transferExists();
        boardingLeg("STOP-A", null);

        RerouteOrigin origin = resolver
                .resolveTransferWait(JOURNEY_ID, REVISION, TRANSFER_INDEX).orElseThrow();

        assertThat(origin.routeId()).isNull();
        assertThat(origin.seq()).isNull();
        assertThat(origin.isRealtimeAddressable()).isFalse();
    }

    @Test
    @DisplayName("환승 행이 없으면 빈 결과 — 예외가 아니다(옛 주소로 계속 돈다)")
    void missingTransferYieldsEmpty() {
        when(transferRepository.findByJourneyIdAndRouteRevisionAndTransferIndexAndDeletedAtIsNull(
                anyLong(), anyInt(), anyInt())).thenReturn(Optional.empty());

        assertThat(resolver.resolveTransferWait(JOURNEY_ID, REVISION, TRANSFER_INDEX)).isEmpty();
    }

    @Test
    @DisplayName("승차 정류장 id 가 없으면 빈 결과 — stopId 없는 원점은 만들지 않는다")
    void missingStopIdYieldsEmpty() {
        transferExists();
        boardingLeg(null, "ROUTE-1");

        assertThat(resolver.resolveTransferWait(JOURNEY_ID, REVISION, TRANSFER_INDEX)).isEmpty();
    }
}
