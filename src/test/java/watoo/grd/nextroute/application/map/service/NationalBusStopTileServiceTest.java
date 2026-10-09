package watoo.grd.nextroute.application.map.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.map.dto.BusStopTileSnapshot;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPatternUnavailableException;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStop;
import watoo.grd.nextroute.application.route.routing.OtpConcurrencyGate;
import watoo.grd.nextroute.application.route.routing.OtpPermit;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;
import watoo.grd.nextroute.domain.bus.repository.BusStopSnapshotProjection;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("전국 버스정류장 타일 빌드")
class NationalBusStopTileServiceTest {

    private final TransitPatternPort port = mock(TransitPatternPort.class);
    private final OtpConcurrencyGate gate = mock(OtpConcurrencyGate.class);
    private final BusStopRepository busStopRepository = mock(BusStopRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-10T00:00:00Z"));
    /** 같은 스레드에서 바로 돈다. */
    private final ExecutorService direct = mock(ExecutorService.class);
    private NationalBusStopTileService service;

    private static final TransitStop SEB = new TransitStop("1:BS_TAGO_SEB121000009", "신분당선강남역",
            37.4958, 127.028999, RoutingMode.BUS);
    private static final TransitStop GGB = new TransitStop("1:BS_TAGO_GGB121000009", "신분당선강남역(중)",
            37.495783, 127.028567, RoutingMode.BUS);
    private static final TransitStop SUBWAY = new TransitStop("1:RS_ACC1_S-1-0222", "강남(2호선)",
            37.4981, 127.0280, RoutingMode.SUBWAY);
    /** 37·127 블록과 38·127 블록 경계 위(위도 38.0) — 두 블록에 다 온다. */
    private static final TransitStop EDGE = new TransitStop("1:BS_TAGO_GGB200000001", "경계", 38.0, 127.5,
            RoutingMode.BUS);

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(direct).execute(any());
        given(gate.tryAcquire(any(), eq(NationalBusStopTileService.GATE_ROLE)))
                .willReturn(Optional.of(new OtpPermit("p")));
        given(port.stopsInBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any())).willReturn(List.of());
        given(port.stopsInBox(eq(37.0), eq(127.0), eq(38.0), eq(128.0), any()))
                .willReturn(List.of(SEB, GGB, SUBWAY, EDGE));
        given(port.stopsInBox(eq(38.0), eq(127.0), eq(39.0), eq(128.0), any())).willReturn(List.of(EDGE));
        BusStopSnapshotProjection row = mock(BusStopSnapshotProjection.class);
        given(row.getStopId()).willReturn("121000009");
        given(row.getArsId()).willReturn("22010");
        given(busStopRepository.findAllForSnapshot()).willReturn(List.of(row));
        service = newService(1);
    }

    private NationalBusStopTileService newService(int minCount) {
        NationalBusStopTileService s = new NationalBusStopTileService(port, gate, busStopRepository, mapper, clock,
                minCount, direct);
        s.pauseBetweenBlocksMs = 0;
        s.acquireRetryMs = 0;
        return s;
    }

    @Test
    @DisplayName("빌드: 48 블록, 버스만, 같은 정류장 한 점(서울), 경계 중복 제거, 서울 ARS, permit 반납")
    void builds() throws Exception {
        service.check();

        BusStopTileSnapshot snapshot = service.current().orElseThrow();
        assertThat(snapshot.count()).isEqualTo(2);
        assertThat(snapshot.tiles()).containsOnlyKeys("374_1270", "380_1275");
        JsonNode tile = mapper.readTree(snapshot.tiles().get("374_1270").json());
        assertThat(tile.path("columns").toString()).isEqualTo("[\"gtfsStopId\",\"name\",\"lat\",\"lng\",\"arsId\"]");
        assertThat(tile.path("stops")).hasSize(1);
        assertThat(tile.path("stops").get(0).toString())
                .isEqualTo("[\"1:BS_TAGO_SEB121000009\",\"신분당선강남역\",37.4958,127.029,\"22010\"]");
        assertThat(tile.path("version").asText()).isEqualTo(snapshot.tiles().get("374_1270").version());
        assertThat(mapper.readTree(snapshot.tiles().get("380_1275").json()).path("stops").get(0).get(4).isNull())
                .isTrue();
        JsonNode manifest = mapper.readTree(snapshot.manifestJson());
        assertThat(manifest.path("version").asText()).isEqualTo(snapshot.version());
        assertThat(manifest.path("tiles").path("374_1270").asText())
                .isEqualTo(snapshot.tiles().get("374_1270").version());
        assertThat(manifest.path("count").asInt()).isEqualTo(2);
        verify(port, times(48)).stopsInBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any());
        verify(gate, times(48)).release(any());
    }

    @Test
    @DisplayName("같은 데이터로 다시 만들면 버전이 같다 — 클라는 다시 받지 않는다")
    void stableVersion() {
        BusStopTileSnapshot first = service.build(List.of(SEB, GGB, EDGE));
        BusStopTileSnapshot second = service.build(List.of(EDGE, GGB, SEB));

        assertThat(second.version()).isEqualTo(first.version());
        assertThat(second.tiles().get("374_1270").version()).isEqualTo(first.tiles().get("374_1270").version());
    }

    @Test
    @DisplayName("한 정류장만 바뀌면 그 타일 버전만 바뀐다")
    void onlyChangedTile() {
        BusStopTileSnapshot before = service.build(List.of(SEB, EDGE));
        BusStopTileSnapshot after = service.build(List.of(SEB, new TransitStop(EDGE.gtfsId(), "경계(이름 바뀜)",
                EDGE.lat(), EDGE.lon(), RoutingMode.BUS)));

        assertThat(after.tiles().get("374_1270").version()).isEqualTo(before.tiles().get("374_1270").version());
        assertThat(after.tiles().get("380_1275").version()).isNotEqualTo(before.tiles().get("380_1275").version());
        assertThat(after.version()).isNotEqualTo(before.version());
    }

    @Test
    @DisplayName("첫 빌드 실패(OTP 오류) → 빈 채로 두고, 다음 점검에서 게시")
    void firstBuildFailureRecovers() {
        given(port.stopsInBox(eq(37.0), eq(127.0), eq(38.0), eq(128.0), any()))
                .willThrow(new TransitPatternUnavailableException("down"))
                .willReturn(List.of(SEB));

        service.check();
        assertThat(service.current()).isEmpty();

        service.check();
        assertThat(service.current()).isPresent();
    }

    @Test
    @DisplayName("permit 을 못 잡으면 이번 빌드 포기 — 이전 스냅샷 유지")
    void gateSaturated() {
        service.check();
        BusStopTileSnapshot before = service.current().orElseThrow();
        clock.advanceHours(25);
        given(gate.tryAcquire(any(), anyString())).willReturn(Optional.empty());

        service.check();

        assertThat(service.current()).containsSame(before);
        verify(port, times(48)).stopsInBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any());
    }

    @Test
    @DisplayName("이상치 가드: 하한 미만이거나 이전의 80% 미만이면 게시하지 않는다")
    void sanityGuard() {
        NationalBusStopTileService strict = newService(3);
        strict.check();
        assertThat(strict.current()).isEmpty(); // 2개 < 하한 3

        service.check();
        BusStopTileSnapshot before = service.current().orElseThrow(); // 2개
        clock.advanceHours(25);
        given(port.stopsInBox(eq(37.0), eq(127.0), eq(38.0), eq(128.0), any())).willReturn(List.of());
        given(port.stopsInBox(eq(38.0), eq(127.0), eq(39.0), eq(128.0), any())).willReturn(List.of(EDGE));
        service.check(); // 1개 < 2 * 0.8
        assertThat(service.current()).containsSame(before);
    }

    @Test
    @DisplayName("하루 안에는 다시 만들지 않고, 하루 지나면 다시 만든다")
    void rebuildDaily() {
        service.check();
        service.check();
        verify(port, times(48)).stopsInBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any());

        clock.advanceHours(25);
        service.check();
        verify(port, times(96)).stopsInBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any());
        verify(gate, never()).tryAcquire(any(), eq("api"));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceHours(long hours) {
            now = now.plusSeconds(hours * 3600);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
