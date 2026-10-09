package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.StationResult;
import watoo.grd.nextroute.application.route.mapping.BusRealtimeStopIds;
import watoo.grd.nextroute.domain.journey.entity.JourneyRevision;
import watoo.grd.nextroute.domain.journey.entity.JourneyRevisionStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyRevisionRepository;
import watoo.grd.nextroute.testsupport.InMemoryRegionalBusRouteStopStore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 계획 정류장(앱이 남은 정류장 수를 세는 목록) 계약 — 그 개정본 스냅샷의 GTFS 정류장을 실시간 id 로 준다.
 */
class JourneyPlannedBusStopReaderTest {

    private static final long JOURNEY_ID = 7L;
    private static final int RIDE_LEG_INDEX = 1;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JourneyRevisionRepository revisionRepository;
    private JourneyPlannedBusStopReader reader;

    @BeforeEach
    void setUp() {
        revisionRepository = mock(JourneyRevisionRepository.class);
        reader = new JourneyPlannedBusStopReader(revisionRepository,
                new JourneyCompiler(new StopIdentityResolver()), objectMapper,
                new BusRealtimeStopIds(InMemoryRegionalBusRouteStopStore.prefixTableOnlyLookup()));
    }

    @Test
    @DisplayName("승차부터 하차까지 순서대로, 실시간 id 로 준다(TAGO 는 접두사 포함)")
    void readsPlannedStopsAsRealtimeIds() {
        givenRevision(1, path("1:BS_TAGO_DJB9007519", "1:BS_TAGO_DJB8007600", "1:BS_TAGO_DJB8001311"));

        assertThat(reader.realtimeStopIds(JOURNEY_ID, 1, RIDE_LEG_INDEX))
                .containsExactly("DJB9007519", "DJB8007600", "DJB8001311");
    }

    @Test
    @DisplayName("실시간 id 로 못 바꾸는 정류장은 null 칸으로 남겨 순서를 지킨다")
    void keepsOrderWithUnmappableStops() {
        givenRevision(1, path("1:BS_TAGO_GGB277101594", "weird", "1:BS_TAGO_GGB200000075"));

        assertThat(reader.realtimeStopIds(JOURNEY_ID, 1, RIDE_LEG_INDEX))
                .containsExactly("277101594", null, "200000075");
    }

    @Test
    @DisplayName("재탐색 뒤에는 그 개정본을 읽고, 스냅샷이 없으면 빈 목록이다")
    void readsRequestedRevisionOrEmpty() {
        givenRevision(1, path("1:BS_TAGO_DJB1", "1:BS_TAGO_DJB2"));
        givenRevision(2, path("1:BS_TAGO_DJB8", "1:BS_TAGO_DJB9"));
        when(revisionRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNull(JOURNEY_ID, 9))
                .thenReturn(Optional.empty());

        assertThat(reader.realtimeStopIds(JOURNEY_ID, 2, RIDE_LEG_INDEX)).containsExactly("DJB8", "DJB9");
        assertThat(reader.realtimeStopIds(JOURNEY_ID, 9, RIDE_LEG_INDEX)).isEmpty();
    }

    private void givenRevision(int routeRevision, PathResult path) {
        JourneyRevision revision = JourneyRevision.builder()
                .journeyId(JOURNEY_ID)
                .routeRevision(routeRevision)
                .status(JourneyRevisionStatus.ACTIVE)
                .routeSnapshot(objectMapper.valueToTree(path))
                .activatedAt(Instant.now())
                .build();
        when(revisionRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNull(
                JOURNEY_ID, routeRevision)).thenReturn(Optional.of(revision));
    }

    /** 버스 하나. 쪼개기가 대기·타기를 붙이므로 타는 단계는 {@link #RIDE_LEG_INDEX} 다. */
    private PathResult path(String... stationIds) {
        StationResult[] stations = new StationResult[stationIds.length];
        for (int i = 0; i < stationIds.length; i++) {
            stations[i] = new StationResult(i, stationIds[i], "정류장" + i, 127.0 + i / 100.0, 36.0);
        }
        return new PathResult(1, null,
                List.of(SubPathFixture.bus(15)
                        .names("정류장0", "정류장" + (stationIds.length - 1))
                        .stations(stations)
                        .lanes(SubPathFixture.busLane("1001", "R")).build()),
                List.of());
    }
}
