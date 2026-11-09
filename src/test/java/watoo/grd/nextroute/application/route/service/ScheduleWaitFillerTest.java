package watoo.grd.nextroute.application.route.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;
import watoo.grd.nextroute.application.route.dto.LaneResult;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.route.dto.TransferArrival;
import watoo.grd.nextroute.application.route.dto.TransferArrival.Source;
import watoo.grd.nextroute.application.route.dto.TransferArrival.Status;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** search-regional-bus-wait PR-1b: 실시간·모델을 못 물은 버스를 OTP 계획 탑승시각으로 채운다. */
class ScheduleWaitFillerTest {

    private static final Instant NOW = Instant.parse("2026-11-09T05:00:00Z");

    /** 기존 검색 시험용 — 시간표 대기를 끈 필러(그 시험들의 관심사를 바꾸지 않는다). */
    public static ScheduleWaitFiller disabled() {
        TransferArrivalProperties props = new TransferArrivalProperties();
        props.setScheduleFallbackEnabled(false);
        return new ScheduleWaitFiller(props, new RouteSearchMetrics(new SimpleMeterRegistry()));
    }

    private TransferArrivalProperties props;
    private SimpleMeterRegistry registry;
    private ScheduleWaitFiller filler;

    @BeforeEach
    void setUp() {
        props = new TransferArrivalProperties();
        registry = new SimpleMeterRegistry();
        filler = new ScheduleWaitFiller(props, new RouteSearchMetrics(registry));
    }

    private static TransferArrival arrival(Status status, Source source, Instant userAt, Long wait) {
        return new TransferArrival("234000026", 0, source, status, NOW, userAt,
                wait == null ? null : userAt.plusSeconds(wait), wait, null, null, null, false, null);
    }

    private static SubPathResult bus(TransferArrival ta, Instant plannedBoard, Integer plannedWait) {
        SubPathResult sp = new SubPathResult(2, 13, null,
                List.of(new LaneResult("720-2", "720-2", null, null, null, "234000026", null)), List.of(),
                "수원역", "광교", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, "202000208", null, null, null, null, null,
                ta == null ? null : List.of(ta), null, null, null, null);
        return plannedBoard == null ? sp : sp.withPlanned(plannedBoard, plannedBoard.plusSeconds(780), plannedWait);
    }

    private static SubPathResult subway() {
        return new SubPathResult(1, 10, null, List.of(), List.of(), "a", "b", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private static RouteSearchResult result(SubPathResult... sps) {
        PathInfo info = new PathInfo(30, 1500, 0, 0, "출발", "도착", null);
        return new RouteSearchResult(0, 1, 0, 0, 0, List.of(new PathResult(2, info, List.of(sps), List.of())));
    }

    private static TransferArrival first(RouteSearchResult r, int sp) {
        return r.paths().get(0).subPaths().get(sp).transferArrivals().get(0);
    }

    private double count(String outcome, String reason) {
        var c = registry.find(RouteSearchMetrics.SCHEDULE_WAIT).tag("outcome", outcome).tag("reason", reason).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void 경기_버스_매핑_실패는_계획_탑승까지를_대기로_채운다() {
        Instant userAt = NOW.plusSeconds(300);
        SubPathResult sp = bus(arrival(Status.STOP_MAPPING_FAILED, Source.NONE, userAt, null), NOW.plusSeconds(720), 120);

        TransferArrival ta = first(filler.fill(result(sp), NOW, NOW), 0);

        assertThat(ta.source()).isEqualTo(Source.SCHEDULE);
        assertThat(ta.status()).isEqualTo(Status.AVAILABLE);
        assertThat(ta.waitSeconds()).isEqualTo(420L);
        assertThat(ta.predictedArrivalAt()).isEqualTo(NOW.plusSeconds(720));
        assertThat(ta.userArrivalAt()).isEqualTo(userAt);
        assertThat(ta.fallbackReason()).isEqualTo("STOP_MAPPING_FAILED");
        assertThat(ta.conditional()).isFalse();
        assertThat(count("filled", "STOP_MAPPING_FAILED")).isEqualTo(1);
    }

    @Test
    void 앞_버스_결과_없음은_OTP_계획_대기를_조건부로_쓴다() {
        SubPathResult sp = bus(arrival(Status.UPSTREAM_UNAVAILABLE, Source.NONE, NOW, null), NOW.plusSeconds(2400), 300);

        TransferArrival ta = first(filler.fill(result(sp), NOW, NOW), 0);

        assertThat(ta.source()).isEqualTo(Source.SCHEDULE);
        assertThat(ta.waitSeconds()).isEqualTo(300L);
        assertThat(ta.conditional()).isTrue();
        assertThat(ta.userArrivalAt()).isEqualTo(NOW.plusSeconds(2100));
    }

    @Test
    void 기한_초과로_도착_시각이_없으면_계획_대기를_쓴다() {
        SubPathResult sp = bus(new TransferArrival("234000026", 0, Source.NONE, Status.ERROR, NOW, null,
                null, null, null, null, null, null, null), NOW.plusSeconds(600), 90);

        TransferArrival ta = first(filler.fill(result(sp), NOW, NOW), 0);

        assertThat(ta.waitSeconds()).isEqualTo(90L);
        assertThat(ta.conditional()).isTrue();
        assertThat(ta.fallbackReason()).isEqualTo("ERROR");
    }

    @Test
    void 지하철_뒤_버스는_어림으로_표시한다() {
        SubPathResult sp = bus(arrival(Status.UNSUPPORTED_ROUTE, Source.NONE, NOW.plusSeconds(900), null),
                NOW.plusSeconds(1000), 60);

        TransferArrival ta = first(filler.fill(result(subway(), sp), NOW, NOW), 1);

        assertThat(ta.status()).isEqualTo(Status.ARRIVAL_TIME_APPROXIMATE);
        assertThat(ta.source()).isEqualTo(Source.SCHEDULE);
    }

    @Test
    void 물어보고_차가_없거나_기능이_꺼졌거나_실시간이_있으면_그대로() {
        for (TransferArrival ta : List.of(
                arrival(Status.NO_VEHICLE, Source.MODEL, NOW, null),
                arrival(Status.DISABLED, Source.NONE, NOW, null),
                arrival(Status.AVAILABLE, Source.REALTIME, NOW, 200L))) {
            SubPathResult sp = bus(ta, NOW.plusSeconds(600), 60);
            assertThat(first(filler.fill(result(sp), NOW, NOW), 0)).isEqualTo(ta);
        }
    }

    @Test
    void 계획_버스를_60초_넘게_놓쳤으면_채우지_않고_60초_안이면_대기_0() {
        Instant userAt = NOW.plusSeconds(700);
        TransferArrival late = arrival(Status.STOP_MAPPING_FAILED, Source.NONE, userAt, null);
        assertThat(first(filler.fill(result(bus(late, NOW.plusSeconds(600), 60)), NOW, NOW), 0)).isEqualTo(late);
        assertThat(count("skipped", "missed")).isEqualTo(1);

        TransferArrival close = arrival(Status.STOP_MAPPING_FAILED, Source.NONE, NOW.plusSeconds(640), null);
        assertThat(first(filler.fill(result(bus(close, NOW.plusSeconds(600), 60)), NOW, NOW), 0).waitSeconds())
                .isZero();
    }

    @Test
    void 상한을_넘는_대기와_출발시각_지정_검색과_계획_없는_구간은_채우지_않는다() {
        TransferArrival ta = arrival(Status.STOP_MAPPING_FAILED, Source.NONE, NOW, null);
        assertThat(first(filler.fill(result(bus(ta, NOW.plusSeconds(3700), 60)), NOW, NOW), 0)).isEqualTo(ta);
        assertThat(first(filler.fill(result(bus(ta, NOW.plusSeconds(600), 60)), NOW, NOW.plusSeconds(3600)), 0))
                .isEqualTo(ta);
        assertThat(first(filler.fill(result(bus(ta, null, null)), NOW, NOW), 0)).isEqualTo(ta);
        assertThat(count("skipped", "cap")).isEqualTo(1);
        assertThat(count("skipped", "departure_at")).isEqualTo(1);
        assertThat(count("skipped", "no_plan")).isEqualTo(1);
    }

    @Test
    void 꺼지면_결과가_같은_객체() {
        props.setScheduleFallbackEnabled(false);
        RouteSearchResult in = result(bus(arrival(Status.STOP_MAPPING_FAILED, Source.NONE, NOW, null),
                NOW.plusSeconds(600), 60));

        assertThat(filler.fill(in, NOW, NOW)).isSameAs(in);
    }

    @Test
    void 바꿀_것이_없으면_결과가_같은_객체이고_다른_lane_과_계획_시각은_남는다() {
        RouteSearchResult none = result(bus(arrival(Status.AVAILABLE, Source.REALTIME, NOW, 100L), NOW.plusSeconds(600), 60));
        assertThat(filler.fill(none, NOW, NOW)).isSameAs(none);

        List<TransferArrival> two = new ArrayList<>();
        two.add(arrival(Status.STOP_MAPPING_FAILED, Source.NONE, NOW, null));
        TransferArrival lane1 = new TransferArrival("x", 1, Source.NONE, Status.STOP_MAPPING_FAILED, NOW, NOW,
                null, null, null, null, null, false, null);
        two.add(lane1);
        SubPathResult sp = bus(null, NOW.plusSeconds(600), 60).withTransferArrivals(two);

        SubPathResult out = filler.fill(result(sp), NOW, NOW).paths().get(0).subPaths().get(0);

        assertThat(out.transferArrivals().get(0).source()).isEqualTo(Source.SCHEDULE);
        assertThat(out.transferArrivals().get(1)).isEqualTo(lane1);
        assertThat(out.plannedBoardAt()).isEqualTo(NOW.plusSeconds(600));
    }
}
