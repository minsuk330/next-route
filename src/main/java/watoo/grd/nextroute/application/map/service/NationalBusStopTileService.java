package watoo.grd.nextroute.application.map.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.map.dto.BusStopTileSnapshot;
import watoo.grd.nextroute.application.map.port.in.GetBusStopTilesUseCase;
import watoo.grd.nextroute.application.route.mapping.OtpGtfsIds;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStop;
import watoo.grd.nextroute.application.route.routing.OtpConcurrencyGate;
import watoo.grd.nextroute.application.route.routing.OtpPermit;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;
import watoo.grd.nextroute.domain.bus.repository.BusStopSnapshotProjection;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 지도 정류장 전국 타일(#326). 원천은 OTP 그래프 정류장(근처 목록·노선·추적과 같은 id 체계).
 *
 * <h2>언제 만드나</h2>
 * 1분마다 점검해 <b>아직 없으면</b> 또는 <b>마지막 빌드가 하루 지났으면</b> 만든다. graphVersion 은 env 정적값이라 런타임에
 * 바뀌지 않으므로 그걸로 감지하지 않는다 — 하루 주기 재빌드가 api 재시작 없는 그래프 교체까지 받는다. 내용 해시라 바뀐
 * 게 없으면 클라 비용은 0. 첫 빌드가 실패해도 다음 점검이 다시 시도하므로 503 은 스스로 풀린다.
 *
 * <h2>어떻게 만드나</h2>
 * 전국 한 번에 받으면 24MB 응답이라 api 힙(Xmx768m) 피크가 크다 → 1° 블록 48칸을 차례로 부른다. 블록마다 OTP permit 을
 * 짧게 잡고 놓으며(role {@code map-build}), 사이에 쉬어 검색 permit 을 독점하지 않는다. {@code tryAcquire} 는 deadline 까지
 * 기다리는 방식이라 acquire 는 짧은 마감 1회, 호출은 새 마감으로 한다.
 *
 * <p>점검은 공용 스케줄러(풀 3)에서 돌지만 빌드는 전용 스레드 하나에서 한다 — 수십 초 빌드가 다른 스케줄 작업을 막지 않게.
 *
 * <h2>게시하지 않는 경우</h2>
 * OTP 가 그래프를 올리는 중이면 빈 블록이 예외 없이 온다. 그대로 게시하면 앱이 manifest 에서 빠진 타일을 지운다 →
 * 합계가 하한 미만이거나 이전의 80% 미만이면 버리고 이전 것을 유지한다.
 */
@Slf4j
@Service
@Profile("!worker")
public class NationalBusStopTileService implements GetBusStopTilesUseCase {

    static final String GATE_ROLE = "map-build";
    static final Duration REBUILD_AFTER = Duration.ofHours(24);
    /** 실측 205,947(2026-10-10). */
    static final int DEFAULT_MIN_COUNT = 150_000;
    static final double MIN_RATIO_OF_PREVIOUS = 0.8;
    static final List<String> COLUMNS = List.of("gtfsStopId", "name", "lat", "lng", "arsId");
    static final double TILE_SIZE = 0.1;

    /** 실측 버스 정류장 범위 위도 33.94~38.54, 경도 124.62~130.92 (울릉·독도 포함되게 넉넉히). */
    private static final int MIN_LAT = 33;
    private static final int MAX_LAT = 39;
    private static final int MIN_LON = 124;
    private static final int MAX_LON = 132;
    private static final long ACQUIRE_MS = 2_000;
    private static final long CALL_MS = 10_000;
    private static final int ACQUIRE_ATTEMPTS = 5;

    private final TransitPatternPort port;
    private final OtpConcurrencyGate gate;
    private final BusStopRepository busStopRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int minCount;
    private final ExecutorService builder;
    long pauseBetweenBlocksMs = 100;
    long acquireRetryMs = 1_000;

    private final AtomicReference<BusStopTileSnapshot> current = new AtomicReference<>();
    private final AtomicBoolean building = new AtomicBoolean();

    @Autowired
    public NationalBusStopTileService(TransitPatternPort port, OtpConcurrencyGate gate,
                                      BusStopRepository busStopRepository, ObjectMapper objectMapper, Clock clock) {
        this(port, gate, busStopRepository, objectMapper, clock, DEFAULT_MIN_COUNT,
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "bus-stop-tiles");
                    t.setDaemon(true);
                    return t;
                }));
    }

    NationalBusStopTileService(TransitPatternPort port, OtpConcurrencyGate gate,
                               BusStopRepository busStopRepository, ObjectMapper objectMapper, Clock clock,
                               int minCount, ExecutorService builder) {
        this.port = port;
        this.gate = gate;
        this.busStopRepository = busStopRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.minCount = minCount;
        this.builder = builder;
    }

    @Override
    public Optional<BusStopTileSnapshot> current() {
        return Optional.ofNullable(current.get());
    }

    /** 부팅 직후 바로, 이후 1분마다. 필요할 때만 빌드를 전용 스레드에 건다. */
    @Scheduled(initialDelay = 0, fixedDelay = 60_000)
    public void check() {
        BusStopTileSnapshot snapshot = current.get();
        boolean due = snapshot == null || !snapshot.builtAt().plus(REBUILD_AFTER).isAfter(clock.instant());
        if (due && building.compareAndSet(false, true)) {
            builder.execute(() -> {
                try {
                    rebuild();
                } finally {
                    building.set(false);
                }
            });
        }
    }

    /** 실패·이상치면 이전 스냅샷을 그대로 둔다. 예외를 밖으로 내지 않는다. */
    void rebuild() {
        long started = System.nanoTime();
        try {
            List<TransitStop> stops = fetchAll();
            if (stops == null) {
                return;
            }
            BusStopTileSnapshot built = build(stops);
            BusStopTileSnapshot previous = current.get();
            if (built.count() < minCount
                    || (previous != null && built.count() < previous.count() * MIN_RATIO_OF_PREVIOUS)) {
                log.warn("[MapTiles] 정류장 수 이상 — 게시하지 않음 count={} previous={} min={}",
                        built.count(), previous == null ? null : previous.count(), minCount);
                return;
            }
            current.set(built);
            log.info("[MapTiles] 버스정류장 타일 빌드 version={} tiles={} stops={} ms={}", built.version(),
                    built.tiles().size(), built.count(), (System.nanoTime() - started) / 1_000_000);
        } catch (RuntimeException e) {
            log.warn("[MapTiles] 타일 빌드 실패 — 이전 스냅샷 유지: {}", e.toString());
        }
    }

    /** 블록 하나라도 못 받으면 null(이번 빌드 포기). */
    private List<TransitStop> fetchAll() {
        Map<String, TransitStop> byId = new LinkedHashMap<>();
        for (int lat = MIN_LAT; lat < MAX_LAT; lat++) {
            for (int lon = MIN_LON; lon < MAX_LON; lon++) {
                List<TransitStop> block = fetchBlock(lat, lon);
                if (block == null) {
                    log.warn("[MapTiles] OTP permit 을 못 잡아 이번 빌드 포기 block={},{}", lat, lon);
                    return null;
                }
                for (TransitStop stop : block) {
                    if (stop.mode() == RoutingMode.BUS) {
                        byId.putIfAbsent(stop.gtfsId(), stop); // 블록 경계 위 정류장은 이웃 블록에도 온다
                    }
                }
                pause(pauseBetweenBlocksMs);
            }
        }
        return new ArrayList<>(byId.values());
    }

    private List<TransitStop> fetchBlock(int lat, int lon) {
        for (int attempt = 0; attempt < ACQUIRE_ATTEMPTS; attempt++) {
            Optional<OtpPermit> permit = gate.tryAcquire(RoutingDeadline.of(clock, ACQUIRE_MS), GATE_ROLE);
            if (permit.isPresent()) {
                try {
                    return port.stopsInBox(lat, lon, lat + 1, lon + 1, RoutingDeadline.of(clock, CALL_MS));
                } finally {
                    gate.release(permit.get());
                }
            }
            pause(acquireRetryMs);
        }
        return null;
    }

    BusStopTileSnapshot build(List<TransitStop> stops) {
        Map<String, String> arsIds = seoulArsIds();
        SortedMap<String, List<TransitStop>> byTile = BusStopTiles.byTile(BusStopTiles.mergeSiblings(stops));
        Map<String, BusStopTileSnapshot.Tile> tiles = new HashMap<>();
        SortedMap<String, String> versions = new TreeMap<>();
        int count = 0;
        for (Map.Entry<String, List<TransitStop>> entry : byTile.entrySet()) {
            List<Object[]> rows = new ArrayList<>(entry.getValue().size());
            for (TransitStop stop : entry.getValue()) {
                rows.add(new Object[] {stop.gtfsId(), stop.name(), BusStopTiles.plain(stop.lat()),
                        BusStopTiles.plain(stop.lon()), arsIds.get(stop.gtfsId())});
            }
            count += rows.size();
            String version = BusStopTiles.sha12(serialize(rows));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("key", entry.getKey());
            body.put("version", version);
            body.put("columns", COLUMNS);
            body.put("stops", rows);
            tiles.put(entry.getKey(), new BusStopTileSnapshot.Tile(version, serialize(body)));
            versions.put(entry.getKey(), version);
        }
        String version = BusStopTiles.manifestVersion(versions);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("version", version);
        manifest.put("tileSize", TILE_SIZE);
        manifest.put("count", count);
        manifest.put("tiles", versions);
        return new BusStopTileSnapshot(version, count, clock.instant(), Map.copyOf(tiles), serialize(manifest));
    }

    /** 서울 정류장만 ARS 번호가 있다(서울 DB). gtfsId 로 찾는다. */
    private Map<String, String> seoulArsIds() {
        Map<String, String> arsIds = new HashMap<>();
        for (BusStopSnapshotProjection row : busStopRepository.findAllForSnapshot()) {
            String gtfsId = OtpGtfsIds.seoulBusStop(row.getStopId());
            if (gtfsId != null && row.getArsId() != null) {
                arsIds.put(gtfsId, row.getArsId());
            }
        }
        return arsIds;
    }

    private byte[] serialize(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("버스정류장 타일 직렬화 실패", e);
        }
    }

    private static void pause(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
