package watoo.grd.nextroute.application.route.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.subway.dto.TrainKind;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** otp-kakao-parity 순수 규칙: 느린 경로 줄이기, 지하철 대안 노선 고르기, 예매 열차 짧은 구간. 예시는 2026-11-16 운영 실측. */
class KakaoParityRulesTest {

    private static final Instant T0 = Instant.parse("2026-11-16T05:00:00Z");

    @Test
    @DisplayName("합정→잠실: 최단 41분, 66분 넘는 경로는 빠른 2개(91·96분)만 — 순서는 입력 그대로")
    void trimsSlowRoutesKeepingTwoFastest() {
        RoutingItinerary a = it(41, bus("a")), b = it(44, bus("b")), c = it(96, bus("c")), d = it(102, bus("d")),
                e = it(91, bus("e")), f = it(99, bus("f"));

        assertThat(SlowRouteTrimmer.trim(List.of(a, b, c, d, e, f), 1.6, 2)).containsExactly(a, b, c, e);
    }

    @Test
    @DisplayName("배수 0 이면 줄이지 않는다. 한 개뿐이면 그대로")
    void trimDisabled() {
        List<RoutingItinerary> all = List.of(it(41, bus("a")), it(102, bus("b")), it(103, bus("c")), it(104, bus("d")));

        assertThat(SlowRouteTrimmer.trim(all, 0, 2)).isEqualTo(all);
        assertThat(SlowRouteTrimmer.trim(all.subList(0, 1), 1.6, 2)).hasSize(1);
    }

    @Test
    @DisplayName("지하철 대안: 철도 경로 1개(천호→홍대 5호선→2호선)면 그 노선들을 차례로 뺀다. 상한 2")
    void picksLinesOfBestRailRoute() {
        RoutingItinerary rail = it(46, subway("서울5호선"), walk(), subway("서울2호선"), subway("공항철도"));
        RoutingItinerary bus = it(75, bus("370"));

        assertThat(RailAlternatives.linesToExclude(List.of(rail, bus), 3, 2))
                .contains(List.of("서울5호선", "서울2호선"));
    }

    @Test
    @DisplayName("지하철 대안: 철도 경로가 없으면 철도만 묻고(빈 목록), 3개 이상이면 묻지 않는다. 버스가 섞인 경로는 철도 경로가 아니다")
    void railOnlyCounting() {
        RoutingItinerary mixed = it(50, subway("서울9호선"), bus("603"));
        RoutingItinerary walkOnly = it(60, walk());

        assertThat(RailAlternatives.linesToExclude(List.of(mixed, walkOnly), 3, 2)).contains(List.of());
        assertThat(RailAlternatives.linesToExclude(List.of(it(40, subway("a")), it(41, subway("b")),
                it(42, subway("c"))), 3, 2)).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("예매 열차 짧은 구간: 동탄→수서 SRT 17분은 빼고, 40분 넘는 KTX·ITX-청춘·지하철은 남긴다")
    void dropsShortIntercityHops() {
        RoutingItinerary srt = it(49, rail("[고속]수서천안아산목포", 17, null), subway("수인분당선"));
        RoutingItinerary ktx = it(180, rail("[고속]1경부서울부산", 150, null));
        RoutingItinerary itx = it(38, rail("[일반]ITX청춘(천마산.신내)", 16, TrainKind.ITX), subway("서울4호선"));
        RoutingItinerary mugunghwa = it(50, rail("[일반]1경부서울대전", 20, TrainKind.NORMAL));
        RoutingItinerary gtx = it(48, subway("GTX-A"));

        assertThat(ExpressFilterPolicy.withoutShortIntercityHops(List.of(srt, ktx, itx, mugunghwa, gtx), 40))
                .containsExactly(ktx, itx, gtx);
        assertThat(ExpressFilterPolicy.withoutShortIntercityHops(List.of(srt), 0)).containsExactly(srt);
    }

    private static RoutingItinerary it(int minutes, RoutingLeg... legs) {
        return new RoutingItinerary(T0.plusSeconds(minutes * 60L), minutes * 60, 0, 0, 0, false, List.of(),
                Fare.unknown(), List.of(legs), minutes * 100);
    }

    private static RoutingLeg leg(RoutingMode mode, String name, int rideMinutes) {
        return new RoutingLeg(mode, mode.name(), "1", null, "1:" + name, name, null, null, T0,
                T0.plusSeconds(rideMinutes * 60L), rideMinutes * 60, List.of(), List.of(), LegGeometry.missing("x"),
                Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), Set.of());
    }

    private static RoutingLeg bus(String name) { return leg(RoutingMode.BUS, name, 30); }
    private static RoutingLeg subway(String name) { return leg(RoutingMode.SUBWAY, name, 20); }
    private static RoutingLeg walk() { return leg(RoutingMode.WALK, null, 3); }
    private static RoutingLeg rail(String name, int rideMinutes, TrainKind kind) {
        return leg(RoutingMode.RAIL, name, rideMinutes).withTrainKind(kind);
    }
}
