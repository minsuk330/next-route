package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.TrainKind;
import watoo.grd.nextroute.application.subway.service.SubwayExpressCatalog.ExpressService;
import watoo.grd.nextroute.application.subway.service.SubwayPatternStationMatcher.Candidate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 역 마스터 적재 전후의 카탈로그(subway-express PR-S3 보완). "역 목록이 비었다"(아직 못 읽음 → 다시 잇는다)와 "역 목록은
 * 있는데 이름이 없다"(카탈로그 오류 → 영구히 끈다)를 가른다.
 */
class SubwayExpressCatalogReadinessTest {

    /** 운영 fixture 역 마스터. {@code ready} 가 false 면 모든 노선이 빈 목록(적재 전). */
    private static final class Master implements Function<String, List<Candidate>> {
        final AtomicBoolean ready = new AtomicBoolean();
        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        @Override
        public List<Candidate> apply(String lineId) {
            calls.computeIfAbsent(lineId, k -> new AtomicInteger()).incrementAndGet();
            return ready.get() ? SubwayPatternFixtures.STATIONS.getOrDefault(lineId, List.of()) : List.of();
        }

        int total() {
            return calls.values().stream().mapToInt(AtomicInteger::get).sum();
        }
    }

    @Test
    @DisplayName("① 역 마스터가 빈 첫 호출은 서비스를 끄지 않는다 — 적재 뒤 호출에서 4호선 4401 이 급행으로 판정되고 1004-ansan 으로 해석된다")
    void emptyMasterIsRetried() {
        Master master = new Master();
        SubwayExpressCatalog catalog = SubwayExpressCatalog.of(master);
        SubwayTrainKindClassifier classifier = new SubwayTrainKindClassifier(catalog);

        // 적재 전: 이을 수 없어 서비스는 아직 없지만(보류) 번호 규칙은 파일 정의라 4401 은 이미 급행이다
        assertThat(catalog.services()).isEmpty();
        assertThat(catalog.state().pendingLines()).contains("1004", "1009");
        assertThat(catalog.resolve("1004", "하행", "4401", "오이도", null)).isEmpty();
        assertThat(classifier.classify("1004", "4401", "일반", SubwayExpressType.NORMAL)).isEqualTo(TrainKind.EXPRESS);
        assertThat(classifier.onlyNormalTrains("1009")).as("급행 노선을 일반 전용으로 보지 않는다").isFalse();

        master.ready.set(true);

        assertThat(catalog.services()).extracting(ExpressService::id)
                .containsExactlyInAnyOrderElementsOf(SubwayExpressCatalogTest.SERVICE_IDS);
        assertThat(catalog.state().complete()).isTrue();
        assertThat(catalog.resolve("1004", "하행", "4401", "오이도", null)).map(ExpressService::id)
                .contains("1004-ansan");
        assertThat(classifier.classify("1004", "4401", "일반", SubwayExpressType.NORMAL)).isEqualTo(TrainKind.EXPRESS);
        assertThat(SubwayExpressFixturesResolver.of(catalog).resolve("1004", "하행", "4401", "오이도",
                SubwayExpressFixtures.stationId("1004", "사당"))).map(p -> p.serviceId()).contains("1004-ansan");
    }

    @Test
    @DisplayName("② 역 마스터가 있는데 이름이 없는 서비스만 영구히 끈다 — 다른 서비스는 그대로, 다음 호출에도 다시 잇지 않는다")
    void unknownNameDisablesOnlyThatService() {
        Master master = new Master();
        master.ready.set(true);
        SubwayExpressCatalog catalog = SubwayExpressCatalog.of("""
                {"version":"t","services":[
                  {"id":"1009-bogus","lineId":"1009","label":"급행","listedDirection":"상행","match":{"kind":"EXPRESS"},
                   "stops":[{"name":"김포공항"},{"name":"없는역"},{"name":"중앙보훈병원"}]},
                  {"id":"1004-ok","lineId":"1004","label":"급행","match":{"trainNoRanges":[["4400","4499"]],
                   "trainNoAuthoritative":true},"stops":[{"name":"사당"},{"name":"금정"},{"name":"오이도"}]}]}
                """, master);

        assertThat(catalog.services()).extracting(ExpressService::id).containsExactly("1004-ok");
        assertThat(catalog.state().complete()).isTrue();
        assertThat(catalog.resolve("1009", "상행", "9600", "중앙보훈병원", null)).isEmpty();
        assertThat(catalog.resolve("1004", "하행", "4401", "오이도", null)).map(ExpressService::id).contains("1004-ok");

        int calls = master.total();
        assertThat(catalog.services()).extracting(ExpressService::id).containsExactly("1004-ok");
        assertThat(master.total()).as("완성 상태는 다시 잇지 않는다").isEqualTo(calls);
    }

    @Test
    @DisplayName("완성 뒤에는 역 목록을 다시 읽지 않는다(정상 경로는 volatile 읽기 하나)")
    void steadyStateDoesNotReResolve() {
        Master master = new Master();
        master.ready.set(true);
        SubwayExpressCatalog catalog = SubwayExpressCatalog.of(master);
        catalog.services();
        int calls = master.total();

        for (int i = 0; i < 50; i++) {
            catalog.services();
            catalog.resolve("1009", "상행", "9600", "중앙보훈병원", null);
            catalog.stationId("1009", "가양");
            catalog.isTrainNoAuthoritativeExpress("1004", "4401");
        }

        assertThat(master.total()).isEqualTo(calls);
    }

    @Test
    @DisplayName("보류 중 재시도는 간격마다 한 번 — 그 사이 호출은 역 목록을 읽지 않고 지금 상태로 답한다")
    void retryIsThrottled() {
        Master master = new Master();
        SubwayExpressCatalog catalog = SubwayExpressCatalog.of("""
                {"version":"t","services":[{"id":"1009-x","lineId":"1009","listedDirection":"상행",
                  "match":{"kind":"EXPRESS"},"stops":[{"name":"김포공항"},{"name":"중앙보훈병원"}]}]}
                """, master, Duration.ofHours(1));

        catalog.services();
        int calls = master.total();
        master.ready.set(true);
        for (int i = 0; i < 20; i++) {
            assertThat(catalog.services()).isEmpty();
        }

        assertThat(master.total()).isEqualTo(calls);
    }

    @Test
    @DisplayName("동시 첫 호출은 한 번만 잇는다 — 모두 같은 완성 상태를 본다")
    void concurrentFirstCallsResolveOnce() throws Exception {
        Master master = new Master();
        master.ready.set(true);
        SubwayExpressCatalog catalog = SubwayExpressCatalog.of(master);
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return catalog.services().size();
                }));
            }
            start.countDown();
            for (Future<Integer> f : results) {
                assertThat(f.get(10, TimeUnit.SECONDS)).isEqualTo(SubwayExpressCatalogTest.SERVICE_IDS.size());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(master.calls).allSatisfy((line, count) -> assertThat(count.get()).as(line).isEqualTo(1));
    }

    /** fixture 패턴으로 각역 구간을 펼치는 해석기. */
    private static final class SubwayExpressFixturesResolver {
        static SubwayStopPatternResolver of(SubwayExpressCatalog catalog) {
            return new SubwayStopPatternResolver(catalog, SubwayExpressFixtures.lineOrder());
        }
    }
}
