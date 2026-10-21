package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.subway.service.SubwayStopPatternResolver.ResolvedPattern;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 급행 구간 시간(subway-express PR-S4): 정차열이 <b>정확히 같은</b> OTP 패턴만 쓴다. 운영 fixture(2026-10-07)의 9호선 급행
 * 노선({@code 09-2D}, 실시간 상행)과 1호선 경인 급행({@code 01-4D}, 하행)으로 돈다.
 */
@DisplayName("노선 순서 — 급행 정차 패턴의 구간 시간")
class SubwayLineOrderExpressTest {

    private final SubwayLineOrder lineOrder = SubwayExpressFixtures.fixtureLineOrder();
    private final SubwayStopPatternResolver resolver = new SubwayStopPatternResolver(SubwayExpressFixtures.catalog(),
            lineOrder);

    private static String id(String line, String name) {
        return SubwayExpressFixtures.stationId(line, name);
    }

    @Test
    @DisplayName("9호선 급행 가양→염창: OTP 급행 패턴의 운행 시간(완행 합보다 짧다)")
    void line9ExpressSegmentFromExpressPattern() {
        ResolvedPattern express = resolver.resolve("1009", "상행", "9600", "중앙보훈병원", null).orElseThrow();

        Optional<SubwayLineOrder.SegmentTimes> times = lineOrder.segmentTimes("1009", "상행", id("1009", "가양"),
                id("1009", "염창"), express);
        int allStopRun = lineOrder.segmentTimes("1009", "상행", id("1009", "가양"), id("1009", "증미")).orElseThrow()
                .runSeconds()
                + lineOrder.segmentTimes("1009", "상행", id("1009", "증미"), id("1009", "등촌")).orElseThrow().runSeconds()
                + lineOrder.segmentTimes("1009", "상행", id("1009", "등촌"), id("1009", "염창")).orElseThrow().runSeconds();

        assertThat(times).isPresent();
        System.out.printf("[express] 9호선 가양→염창 급행 %ds, 완행 운행 합 %ds%n", times.get().runSeconds(), allStopRun);
        assertThat(times.get().runSeconds()).isLessThan(allStopRun);
        assertThat(lineOrder.arrivalGapSeconds("1009", "상행", id("1009", "가양"), id("1009", "신논현"), express))
                .hasValueSatisfying(gap -> assertThat(gap).isBetween(900, 2000));
    }

    @Test
    @DisplayName("정차열이 붙어 있지 않은 두 역(급행이 서지 않는 역 포함)은 empty — 지어내지 않는다")
    void notAdjacentInPatternIsEmpty() {
        ResolvedPattern express = resolver.resolve("1009", "상행", "9600", "중앙보훈병원", null).orElseThrow();

        assertThat(lineOrder.segmentTimes("1009", "상행", id("1009", "가양"), id("1009", "증미"), express)).isEmpty();
        assertThat(lineOrder.segmentTimes("1009", "상행", id("1009", "가양"), id("1009", "당산"), express)).isEmpty();
    }

    @Test
    @DisplayName("겹치는 구간의 정차열이 하나라도 다르면 그 패턴을 쓰지 않는다(다른 급행 서비스·완행)")
    void differentStopSequenceIsEmpty() {
        ResolvedPattern express = resolver.resolve("1009", "상행", "9600", "중앙보훈병원", null).orElseThrow();
        // 열차 정차열에서 여의도를 뺀 가짜 서비스 — OTP 어느 패턴과도 정확히 같지 않다
        List<String> stops = new java.util.ArrayList<>(express.stopIds());
        stops.remove(id("1009", "여의도"));
        ResolvedPattern skipping = new ResolvedPattern(express.service(), "상행", stops, null);

        assertThat(lineOrder.segmentTimes("1009", "상행", id("1009", "당산"), id("1009", "노량진"), skipping)).isEmpty();
        assertThat(lineOrder.segmentTimes("1009", "상행", id("1009", "가양"), id("1009", "염창"), skipping)).isEmpty();
    }

    @Test
    @DisplayName("패턴이 null(일반 열차)이면 예전 계산 그대로")
    void nullPatternDelegates() {
        String a = id("1009", "가양");
        String b = id("1009", "증미");

        assertThat(lineOrder.segmentTimes("1009", "상행", a, b, null))
                .isEqualTo(lineOrder.segmentTimes("1009", "상행", a, b));
        assertThat(lineOrder.arrivalGapSeconds("1009", "상행", a, b, null))
                .isEqualTo(lineOrder.arrivalGapSeconds("1009", "상행", a, b));
    }

    @Test
    @DisplayName("정확 일치: 앞뒤로 겹치는 동안 끝까지 같아야 한다, 어긋나면 -1")
    void exactMatchRule() {
        List<StationsAheadCalculator.Stop> stops = List.of(stop("A"), stop("C"), stop("E"), stop("F"));

        assertThat(SubwayLineOrder.exactMatch(stops, List.of("A", "C", "E"), 0, 1)).isEqualTo(0);
        assertThat(SubwayLineOrder.exactMatch(stops, List.of("X", "C", "E", "F", "G"), 1, 2))
                .as("앞 X 는 패턴 밖(시작 전)이라 겹치지 않는다").isEqualTo(-1);
        assertThat(SubwayLineOrder.exactMatch(stops, List.of("C", "E", "F", "G"), 0, 1)).isEqualTo(1);
        assertThat(SubwayLineOrder.exactMatch(stops, List.of("A", "B", "C"), 0, 1)).isEqualTo(-1);
    }

    private static StationsAheadCalculator.Stop stop(String id) {
        return new StationsAheadCalculator.Stop(id, id, id, 0, 0);
    }
}
