package watoo.grd.nextroute.application.arrival.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.arrival.config.BusStopArrivalProperties;
import watoo.grd.nextroute.application.arrival.dto.BusStopArrivalResponse;
import watoo.grd.nextroute.application.arrival.dto.GyeonggiBusArrival;
import watoo.grd.nextroute.application.arrival.dto.GyeonggiStopArrival;
import watoo.grd.nextroute.application.arrival.dto.SeoulStationArrival;
import watoo.grd.nextroute.application.arrival.dto.StopArrivals;
import watoo.grd.nextroute.application.arrival.dto.StopArrivals.Status;
import watoo.grd.nextroute.application.arrival.dto.TagoBusArrival;
import watoo.grd.nextroute.application.arrival.dto.TagoStopArrival;
import watoo.grd.nextroute.application.arrival.port.out.BusStopArrivalCachePort;
import watoo.grd.nextroute.application.arrival.port.out.BusStopCatalogPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;

/** 정류장 단위 도착 서비스(#577): 어느 공급자를 부르는지, 합치기, 캐시, 묶음, 기한. */
class BusStopArrivalQueryServiceTest {

    // ── 가짜 카탈로그 ──
    private final Map<String, String> seoulArs = new HashMap<>();
    private final java.util.Set<String> ggStops = new java.util.HashSet<>();
    private final Map<String, List<String>> tagoCities = new HashMap<>();
    private final BusStopCatalogPort catalog = new BusStopCatalogPort() {
        @Override public Optional<String> seoulArsId(String stopId) { return Optional.ofNullable(seoulArs.get(stopId)); }
        @Override public boolean isGyeonggiStop(String stopId) { return ggStops.contains(stopId); }
        @Override public List<String> tagoCityCodes(String stopId) { return tagoCities.getOrDefault(stopId, List.of()); }
    };

    // ── 가짜 공급자: 호출 기록 + 돌려줄 결과 ──
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, BusQueryResult<SeoulStationArrival>> seoulAnswers = new ConcurrentHashMap<>();
    private final Map<String, BusQueryResult<GyeonggiStopArrival>> ggAnswers = new ConcurrentHashMap<>();
    private final Map<String, BusQueryResult<TagoStopArrival>> tagoAnswers = new ConcurrentHashMap<>();
    private volatile CountDownLatch seoulGate;

    // ── 메모리 캐시 ──
    private final Map<String, List<BusStopArrivalResponse>> cacheStore = new ConcurrentHashMap<>();
    private final BusStopArrivalCachePort cache = new BusStopArrivalCachePort() {
        @Override public Optional<List<BusStopArrivalResponse>> get(String s, String k) { return Optional.ofNullable(cacheStore.get(s + ":" + k)); }
        @Override public void put(String s, String k, List<BusStopArrivalResponse> rows) { cacheStore.put(s + ":" + k, rows); }
    };

    private ThreadPoolTaskExecutor executor;
    private final BusStopArrivalProperties props = new BusStopArrivalProperties();
    private BusStopArrivalQueryService service;

    @BeforeEach
    void setUp() {
        executor = executor(8);
        service = service(executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    private ThreadPoolTaskExecutor executor(int size) {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(size);
        e.setMaxPoolSize(size);
        e.setQueueCapacity(0);
        e.initialize();
        return e;
    }

    private BusStopArrivalQueryService service(ThreadPoolTaskExecutor exec) {
        return new BusStopArrivalQueryService(catalog,
                (arsId, timeoutMs) -> {
                    calls.add("SEOUL:" + arsId);
                    CountDownLatch gate = seoulGate;
                    if (gate != null) {
                        try { gate.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    }
                    return seoulAnswers.getOrDefault(arsId, BusQueryResult.ok(List.of()));
                },
                stationId -> { calls.add("GG:" + stationId); return ggAnswers.getOrDefault(stationId, BusQueryResult.ok(List.of())); },
                (city, nodeId) -> { calls.add("TAGO:" + city + ":" + nodeId); return tagoAnswers.getOrDefault(city + ":" + nodeId, BusQueryResult.ok(List.of())); },
                cache, exec, props);
    }

    private static SeoulStationArrival seoulRow(String routeId, String name, String stId, int tra, String veh) {
        return new SeoulStationArrival(routeId, name, 3, stId, 20, tra + "초후", tra, veh, 18, 3,
                null, null, "0", 0, 0, "종점");
    }

    private static GyeonggiStopArrival ggRow(String routeId, String name, Integer minutes) {
        return new GyeonggiStopArrival(new GyeonggiBusArrival(routeId, minutes, minutes == null ? null : 3, null, null,
                null, null, null, null, minutes == null ? "STOP" : "PASS", minutes == null ? null : "V" + routeId,
                null, null, null, null), name, 13, "종점");
    }

    @Test
    void 서울과_경기가_같이_쓰는_9자리_정류장은_둘_다_부르고_합친다() {
        seoulArs.put("121000009", "22009");
        ggStops.add("121000009");
        seoulAnswers.put("22009", BusQueryResult.ok(List.of(seoulRow("100100070", "441", "121000009", 120, "S1"))));
        ggAnswers.put("121000009", BusQueryResult.ok(List.of(ggRow("200000296", "5100", 4), ggRow("100100070", "441", null))));

        List<BusStopArrivalResponse> rows = service.getStopArrivals("121000009");

        assertThat(calls).containsExactlyInAnyOrder("SEOUL:22009", "GG:121000009");
        assertThat(rows).extracting(BusStopArrivalResponse::routeId).containsExactly("100100070", "200000296");
        assertThat(rows.get(0).routeTypeSource()).as("같은 노선 id 는 시간이 있는 서울 줄 하나").isEqualTo("SEOUL");
    }

    @Test
    void 서울_카탈로그에만_있으면_서울만_경기에만_있으면_경기만() {
        seoulArs.put("121000091", "22167");
        ggStops.add("201000107");

        service.getStopArrivals("121000091");
        service.getStopArrivals("201000107");

        assertThat(calls).containsExactly("SEOUL:22167", "GG:201000107");
    }

    @Test
    void 어느_카탈로그에도_없는_정류장은_공급자를_부르지_않는다() {
        List<StopArrivals> result = service.getStopsArrivals(List.of("199999999", "hello", "../etc"));

        assertThat(calls).isEmpty();
        assertThat(result).extracting(StopArrivals::status).containsOnly(Status.UNKNOWN_STOP);
        assertThat(service.getStopArrivals("199999999")).isEmpty();
    }

    @Test
    void TAGO_정류장은_도시코드마다_부른다() {
        tagoCities.put("MPB100100003", List.of("36010", "36420"));
        tagoAnswers.put("36010:MPB100100003", BusQueryResult.ok(List.of(
                new TagoStopArrival(new TagoBusArrival("MPB1", 300, 3, null), "1", "일반버스"))));
        tagoAnswers.put("36420:MPB100100003", BusQueryResult.ok(List.of(
                new TagoStopArrival(new TagoBusArrival("MPB2", 100, 1, null), "200", "농어촌버스"))));

        List<BusStopArrivalResponse> rows = service.getStopArrivals("MPB100100003");

        assertThat(calls).containsExactlyInAnyOrder("TAGO:36010:MPB100100003", "TAGO:36420:MPB100100003");
        assertThat(rows).extracting(BusStopArrivalResponse::routeId).containsExactly("MPB2", "MPB1");
    }

    @Test
    void TAGO_카탈로그에_없으면_접두사_표로_도시를_찾는다() {
        service.getStopArrivals("BSB199080301");

        assertThat(calls).containsExactly("TAGO:21:BSB199080301");
    }

    @Test
    void 성공한_결과는_빈_결과도_캐시하고_다음엔_부르지_않는다() {
        seoulArs.put("121000091", "22167");

        service.getStopArrivals("121000091");
        service.getStopArrivals("121000091");

        assertThat(calls).containsExactly("SEOUL:22167");
        assertThat(cacheStore).containsKey("SEOUL:22167:121000091");
        assertThat(cacheStore.get("SEOUL:22167:121000091")).isEmpty();
    }

    @Test
    void 실패는_캐시하지_않고_그_정류장_상태로_알린다() {
        seoulArs.put("121000091", "22167");
        seoulAnswers.put("22167", BusQueryResult.limited());

        List<StopArrivals> result = service.getStopsArrivals(List.of("121000091"));

        assertThat(result.get(0).status()).isEqualTo(Status.LIMITED);
        assertThat(result.get(0).arrivals()).isEmpty();
        assertThat(cacheStore).isEmpty();
        service.getStopArrivals("121000091");
        assertThat(calls).hasSize(2);
    }

    @Test
    void 공급자_하나만_성공해도_OK_이고_그_공급자_줄을_준다() {
        seoulArs.put("121000009", "22009");
        ggStops.add("121000009");
        seoulAnswers.put("22009", BusQueryResult.blocked());
        ggAnswers.put("121000009", BusQueryResult.ok(List.of(ggRow("200000296", "5100", 4))));

        StopArrivals stop = service.getStopsArrivals(List.of("121000009")).get(0);

        assertThat(stop.status()).isEqualTo(Status.OK);
        assertThat(stop.arrivals()).extracting(BusStopArrivalResponse::routeId).containsExactly("200000296");
    }

    @Test
    void 묶음은_요청_순서를_지키고_공백과_중복을_뺀다() {
        seoulArs.put("A00000001".replace("A", "1"), "1");
        ggStops.add("200000001");

        List<StopArrivals> result = service.getStopsArrivals(List.of(" 200000001", "100000001", "200000001 ", ""));

        assertThat(result).extracting(StopArrivals::stopId).containsExactly("200000001", "100000001");
    }

    @Test
    void 정류장이_10곳을_넘으면_거절한다() {
        List<String> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(String.valueOf(100000000 + i));
        }
        assertThatThrownBy(() -> service.getStopsArrivals(eleven)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 기한을_넘긴_공급자는_LIMITED_로_두고_나머지는_돌려준다() {
        props.setDeadlineMs(200);
        seoulArs.put("121000009", "22009");
        ggStops.add("121000009");
        ggAnswers.put("121000009", BusQueryResult.ok(List.of(ggRow("200000296", "5100", 4))));
        seoulGate = new CountDownLatch(1);   // 서울은 막혀 있다

        long started = System.nanoTime();
        StopArrivals stop = service.getStopsArrivals(List.of("121000009")).get(0);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        seoulGate.countDown();

        assertThat(elapsedMs).isLessThan(1000);
        assertThat(stop.status()).isEqualTo(Status.OK);
        assertThat(stop.arrivals()).extracting(BusStopArrivalResponse::routeId).containsExactly("200000296");
    }

    @Test
    void 실행기가_꽉_차면_기다리지_않고_LIMITED() throws Exception {
        ThreadPoolTaskExecutor tiny = executor(1);
        try {
            BusStopArrivalQueryService narrow = service(tiny);
            seoulArs.put("100000001", "1");
            seoulArs.put("100000002", "2");
            seoulGate = new CountDownLatch(1);
            props.setDeadlineMs(300);

            List<StopArrivals> result = narrow.getStopsArrivals(List.of("100000001", "100000002"));
            seoulGate.countDown();

            assertThat(result).extracting(StopArrivals::status).containsExactly(Status.LIMITED, Status.LIMITED);
        } finally {
            tiny.shutdown();
        }
    }

    @Test
    void 트랜잭션을_걸지_않는다() throws Exception {
        assertThat(BusStopArrivalQueryService.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(BusStopArrivalQueryService.class.getMethod("getStopsArrivals", List.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    void 같은_정류장을_동시에_여럿이_불러도_공급자는_한_번() throws Exception {
        seoulArs.put("121000091", "22167");
        seoulGate = new CountDownLatch(1);
        AtomicInteger done = new AtomicInteger();
        Thread[] threads = new Thread[4];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> { service.getStopArrivals("121000091"); done.incrementAndGet(); });
            threads[i].start();
        }
        Thread.sleep(150);
        seoulGate.countDown();
        for (Thread t : threads) {
            t.join(3000);
        }

        assertThat(done.get()).isEqualTo(4);
        assertThat(calls).containsExactly("SEOUL:22167");
    }

    @Test
    void 같은_arsId_를_쓰는_두_stId_는_서로의_줄을_받지_않는다() {
        seoulArs.put("100000001", "01001");
        seoulArs.put("100000002", "01001");
        seoulAnswers.put("01001", BusQueryResult.ok(List.of(
                seoulRow("R1", "101", "100000001", 60, "V1"),
                seoulRow("R2", "202", "100000002", 90, "V2"))));

        List<StopArrivals> result = service.getStopsArrivals(List.of("100000001", "100000002"));
        List<BusStopArrivalResponse> again = service.getStopArrivals("100000002");

        assertThat(result.get(0).arrivals()).extracting(BusStopArrivalResponse::routeId).containsExactly("R1");
        assertThat(result.get(1).arrivals()).extracting(BusStopArrivalResponse::routeId).containsExactly("R2");
        assertThat(again).as("캐시에서도 제 줄").extracting(BusStopArrivalResponse::routeId).containsExactly("R2");
    }

    @Test
    void 캐시가_다_맞으면_실행기를_쓰지_않는다_10곳_두_공급자도() {
        ThreadPoolTaskExecutor none = executor(1);
        none.shutdown();   // 제출하면 거절된다
        BusStopArrivalQueryService warm = service(none);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String id = String.valueOf(121000000 + i);
            ids.add(id);
            seoulArs.put(id, "A" + i);
            ggStops.add(id);
            cacheStore.put("SEOUL:A" + i + ":" + id, List.of());
            cacheStore.put("GG:" + id, List.of());
        }

        List<StopArrivals> result = warm.getStopsArrivals(ids);

        assertThat(result).extracting(StopArrivals::status).containsOnly(Status.OK);
        assertThat(calls).isEmpty();
    }

    @Test
    void 카탈로그_조회가_실패한_정류장만_ERROR_이고_나머지는_답한다() {
        seoulArs.put("121000091", "22167");
        BusStopCatalogPort broken = new BusStopCatalogPort() {
            @Override public Optional<String> seoulArsId(String stopId) {
                if ("121000009".equals(stopId)) {
                    throw new org.springframework.dao.DataAccessResourceFailureException("pool timeout");
                }
                return catalog.seoulArsId(stopId);
            }
            @Override public boolean isGyeonggiStop(String stopId) { return false; }
            @Override public List<String> tagoCityCodes(String stopId) { return List.of(); }
        };
        BusStopArrivalQueryService svc = new BusStopArrivalQueryService(broken,
                (arsId, timeoutMs) -> { calls.add("SEOUL:" + arsId); return BusQueryResult.ok(List.of()); },
                stationId -> BusQueryResult.ok(List.of()), (city, nodeId) -> BusQueryResult.ok(List.of()),
                cache, executor, props);

        List<StopArrivals> result = svc.getStopsArrivals(List.of("121000009", "121000091"));

        assertThat(result).extracting(StopArrivals::status).containsExactly(Status.ERROR, Status.OK);
        assertThat(svc.getStopArrivals("121000009")).isEmpty();
    }
}
