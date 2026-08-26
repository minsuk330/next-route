package watoo.grd.nextroute.application.map.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.map.dto.BusStopSnapshotPayload;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;
import watoo.grd.nextroute.domain.bus.repository.BusStopSnapshotProjection;
import watoo.grd.nextroute.domain.bus.repository.BusStopSnapshotVersionProjection;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class BusStopSnapshotServiceTest {

    private BusStopRepository busStopRepository;
    private ObjectMapper objectMapper;
    private MutableClock clock;
    private BusStopSnapshotService service;

    @BeforeEach
    void setUp() {
        busStopRepository = mock(BusStopRepository.class);
        objectMapper = new ObjectMapper();
        clock = new MutableClock(Instant.parse("2026-08-19T00:00:00Z"));
        service = new BusStopSnapshotService(busStopRepository, objectMapper, clock);
    }

    @Test
    void version_isCountDashMaxUpdatedEpochSecond() {
        givenVersion(12986L, 1786000000L);

        assertThat(service.currentVersion()).isEqualTo("12986-1786000000");
    }

    @Test
    void emptyTable_yieldsZeroVersionAndEmptyStops() throws Exception {
        givenVersion(0L, 0L);
        given(busStopRepository.findAllForSnapshot()).willReturn(List.of());

        BusStopSnapshotPayload payload = service.snapshot();

        assertThat(payload.version()).isEqualTo("0-0");
        JsonNode body = objectMapper.readTree(payload.json());
        assertThat(body.get("count").asInt()).isZero();
        assertThat(body.get("stops")).isEmpty();
    }

    @Test
    void coordinates_areRoundedToFiveDecimals() throws Exception {
        givenVersion(1L, 100L);
        given(busStopRepository.findAllForSnapshot())
                .willReturn(List.of(row("100000001", "01001", "종로2가사거리", 37.5706234567, 126.9881456789)));

        JsonNode stop = objectMapper.readTree(service.snapshot().json()).get("stops").get(0);

        assertThat(stop.get(3).asText()).isEqualTo("37.57062");
        assertThat(stop.get(4).asText()).isEqualTo("126.98815");
    }

    @Test
    void columnOrder_matchesPayloadRows() throws Exception {
        givenVersion(1L, 100L);
        given(busStopRepository.findAllForSnapshot())
                .willReturn(List.of(row("100000001", "01001", "종로2가사거리", 37.5, 127.0)));

        JsonNode body = objectMapper.readTree(service.snapshot().json());

        assertThat(body.get("columns")).hasSize(5);
        assertThat(body.get("columns").get(0).asText()).isEqualTo("stopId");
        assertThat(body.get("stops").get(0).get(0).asText()).isEqualTo("100000001");
        assertThat(body.get("columns").get(1).asText()).isEqualTo("arsId");
        assertThat(body.get("stops").get(0).get(1).asText()).isEqualTo("01001");
        assertThat(body.get("columns").get(2).asText()).isEqualTo("stopName");
        assertThat(body.get("stops").get(0).get(2).asText()).isEqualTo("종로2가사거리");
    }

    /** arsId 없음을 빈 문자열로 뭉개면 클라가 "안내단말기 없는 정류장"을 구분할 수 없다. */
    @Test
    void nullArsId_staysNull() throws Exception {
        givenVersion(1L, 100L);
        given(busStopRepository.findAllForSnapshot())
                .willReturn(List.of(row("100000001", null, "가상정류장", 37.5, 127.0)));

        JsonNode stop = objectMapper.readTree(service.snapshot().json()).get("stops").get(0);

        assertThat(stop.get(1).isNull()).isTrue();
    }

    @Test
    void sameVersion_reusesCachedBytesWithoutRequeryingRows() {
        givenVersion(12986L, 1786000000L);
        given(busStopRepository.findAllForSnapshot())
                .willReturn(List.of(row("100000001", "01001", "종로2가사거리", 37.5, 127.0)));

        BusStopSnapshotPayload first = service.snapshot();
        clock.advanceSeconds(120); // 프로브 TTL 만료 — 버전은 그대로다.
        BusStopSnapshotPayload second = service.snapshot();

        assertThat(second).isSameAs(first);
        verify(busStopRepository, times(1)).findAllForSnapshot();
        verify(busStopRepository, times(2)).probeSnapshotVersion();
    }

    @Test
    void versionProbe_isMemoizedWithinTtl() {
        givenVersion(12986L, 1786000000L);

        service.currentVersion();
        clock.advanceSeconds(30);
        service.currentVersion();

        verify(busStopRepository, times(1)).probeSnapshotVersion();
    }

    @Test
    void changedVersion_rebuildsPayload() {
        BusStopSnapshotVersionProjection before = version(12986L, 1786000000L);
        BusStopSnapshotVersionProjection after = version(12987L, 1786000099L);
        given(busStopRepository.probeSnapshotVersion()).willReturn(before, after);
        given(busStopRepository.findAllForSnapshot())
                .willReturn(List.of(row("100000001", "01001", "종로2가사거리", 37.5, 127.0)));

        BusStopSnapshotPayload first = service.snapshot();
        clock.advanceSeconds(120);
        BusStopSnapshotPayload second = service.snapshot();

        assertThat(first.version()).isEqualTo("12986-1786000000");
        assertThat(second.version()).isEqualTo("12987-1786000099");
        verify(busStopRepository, times(2)).findAllForSnapshot();
    }

    @Test
    void rowWithNullCoordinate_isSkipped() throws Exception {
        givenVersion(2L, 100L);
        given(busStopRepository.findAllForSnapshot()).willReturn(List.of(
                row("100000001", "01001", "정상", 37.5, 127.0),
                row("100000002", "01002", "좌표없음", null, 127.0)));

        JsonNode body = objectMapper.readTree(service.snapshot().json());

        assertThat(body.get("count").asInt()).isEqualTo(1);
        assertThat(body.get("stops")).hasSize(1);
    }

    private void givenVersion(long count, long epochSec) {
        given(busStopRepository.probeSnapshotVersion()).willReturn(version(count, epochSec));
    }

    private BusStopSnapshotVersionProjection version(long count, long epochSec) {
        return new VersionRow(count, epochSec);
    }

    private BusStopSnapshotProjection row(String stopId, String arsId, String stopName,
                                         Double lat, Double lng) {
        return new StopRow(stopId, arsId, stopName, lat, lng);
    }

    /**
     * projection 은 mock 대신 record 로 만든다 — {@code given(...)} 인자 안에서 helper 가 다시
     * stubbing 하면 Mockito 가 {@code UnfinishedStubbingException} 을 던진다.
     */
    private record VersionRow(long stopCount, long maxUpdatedEpochSec)
            implements BusStopSnapshotVersionProjection {
        @Override public long getStopCount() { return stopCount; }
        @Override public long getMaxUpdatedEpochSec() { return maxUpdatedEpochSec; }
    }

    private record StopRow(String stopId, String arsId, String stopName, Double latitude, Double longitude)
            implements BusStopSnapshotProjection {
        @Override public String getStopId() { return stopId; }
        @Override public String getArsId() { return arsId; }
        @Override public String getStopName() { return stopName; }
        @Override public Double getLatitude() { return latitude; }
        @Override public Double getLongitude() { return longitude; }
    }

    /** 프로브 TTL 을 실제로 넘겨보기 위한 시계. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
