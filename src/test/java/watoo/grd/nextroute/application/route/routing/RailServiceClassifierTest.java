package watoo.grd.nextroute.application.route.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.routing.RailServiceClassifier.PlannedStop;
import watoo.grd.nextroute.application.route.routing.RailServiceClassifier.RailStop;
import watoo.grd.nextroute.application.subway.dto.RailPattern;
import watoo.grd.nextroute.application.subway.dto.TrainKind;
import watoo.grd.nextroute.application.subway.service.SubwayExpressFixtures;
import watoo.grd.nextroute.application.subway.service.SubwayPatternStationMatcher;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 경로 검색 열차 종류 판정(subway-express S2). 노선·패턴·역은 운영 OTP·역 마스터 fixture(2026-10-07)를 그대로 쓴다.
 */
class RailServiceClassifierTest {

    private static final Instant T0 = Instant.parse("2026-10-21T00:00:00Z");

    private static final String L9_EXPRESS_D = "1:RR_ACC1_S-1-09-2D";
    private static final String L9_EXPRESS_LONG = "서울9호선(급행)<하행>";
    private static final String L1_GYEONGIN_D = "1:RR_ACC1_S-1-01-4D";
    private static final String L1_GYEONGIN_LONG = "서울1호선(용산-동인천급행)<하행>";
    private static final String SD_D = "1:RR_ACC1_S-1-SD-1D";

    private final RailServiceClassifier classifier = SubwayExpressFixtures.railServiceClassifier();

    @Nested
    @DisplayName("노선형 급행(9·1호선) — 구간에서 건너뛴 역이 있어야 급행")
    class RouteTypeExpress {

        @Test
        @DisplayName("9호선 급행 가양→염창(증미·등촌 통과) = EXPRESS")
        void line9ExpressSkipping() {
            RoutingLeg leg = leg(L9_EXPRESS_D, "서울9호선", L9_EXPRESS_LONG, pattern(L9_EXPRESS_D, 16), "가양", "염창");

            assertThat(classifier.classify(leg)).isEqualTo(TrainKind.EXPRESS);
        }

        @Test
        @DisplayName("9호선 급행 김포공항→중앙보훈병원(전 구간) = EXPRESS")
        void line9ExpressWhole() {
            RoutingLeg leg = leg(L9_EXPRESS_D, "서울9호선", L9_EXPRESS_LONG, pattern(L9_EXPRESS_D, 16),
                    "김포공항", "중앙보훈병원");

            assertThat(classifier.classify(leg)).isEqualTo(TrainKind.EXPRESS);
        }

        @Test
        @DisplayName("9호선 급행 trip 이어도 붙은 두 정차역 봉은사→종합운동장만 타면 건너뛴 역이 없어 NORMAL(구간 기준)")
        void line9ExpressAdjacentSegmentIsNormal() {
            RoutingLeg leg = leg(L9_EXPRESS_D, "서울9호선", L9_EXPRESS_LONG, pattern(L9_EXPRESS_D, 16),
                    "봉은사", "종합운동장");

            assertThat(classifier.classify(leg)).isEqualTo(TrainKind.NORMAL);
        }

        @Test
        @DisplayName("1호선 '구로 급행'(14xx, 용산-동인천급행 노선의 7역 패턴) 용산→구로 = NORMAL — 노선 이름만 급행")
        void guroExpress14xxIsNormal() {
            List<RailPattern.Stop> guro = patternWhere(L1_GYEONGIN_D, p -> p.size() == 7);
            assertThat(names(guro)).containsExactly("용산(1호선)", "노량진(1호선)", "대방(1호선)", "신길(1호선)", "영등포",
                    "신도림(1호선)", "구로");

            assertThat(classifier.classify(leg(L1_GYEONGIN_D, "서울1호선", L1_GYEONGIN_LONG, guro, "용산", "구로")))
                    .isEqualTo(TrainKind.NORMAL);
            assertThat(classifier.classify(leg(L1_GYEONGIN_D, "서울1호선", L1_GYEONGIN_LONG, guro, "노량진", "신도림")))
                    .isEqualTo(TrainKind.NORMAL);
        }

        @Test
        @DisplayName("1호선 경인 급행(16역 패턴) 용산→부평 = EXPRESS, 같은 trip 의 용산→구로(모든 역 정차 구간) = NORMAL")
        void gyeonginExpress() {
            List<RailPattern.Stop> express = pattern(L1_GYEONGIN_D, 16);

            assertThat(classifier.classify(leg(L1_GYEONGIN_D, "서울1호선", L1_GYEONGIN_LONG, express, "용산", "부평")))
                    .isEqualTo(TrainKind.EXPRESS);
            assertThat(classifier.classify(leg(L1_GYEONGIN_D, "서울1호선", L1_GYEONGIN_LONG, express, "구로", "개봉")))
                    .as("구일 통과").isEqualTo(TrainKind.EXPRESS);
            assertThat(classifier.classify(leg(L1_GYEONGIN_D, "서울1호선", L1_GYEONGIN_LONG, express, "용산", "구로")))
                    .isEqualTo(TrainKind.NORMAL);
        }
    }

    @Nested
    @DisplayName("정차 패턴형 급행(수인분당 SD-1D) — 같은 노선 안 급행 trip")
    class PatternTypeExpress {

        /** 왕십리→고색 급행 패턴(29역, 운영 코드 SD-1D::05). 보정·구성 등을 건너뛴다. */
        private List<RailPattern.Stop> expressPattern() {
            return patternWhere(SD_D, p -> p.size() == 29 && names(p).contains("죽전(수인분당선)")
                    && !names(p).contains("보정"));
        }

        @Test
        @DisplayName("SD-1D::05 죽전→고색 = EXPRESS")
        void jukjeonToGosaekExpress() {
            RoutingLeg leg = leg(SD_D, "수인분당선", "수인분당선<하행>", expressPattern(), "죽전", "고색");

            assertThat(classifier.classify(leg)).isEqualTo(TrainKind.EXPRESS);
        }

        @Test
        @DisplayName("SD-1D::05 왕십리→선릉 = NORMAL(이 구간은 모든 역에 선다)")
        void wangsimniToSeolleungNormal() {
            RoutingLeg leg = leg(SD_D, "수인분당선", "수인분당선<하행>", expressPattern(), "왕십리", "선릉");

            assertThat(classifier.classify(leg)).isEqualTo(TrainKind.NORMAL);
        }

        @Test
        @DisplayName("같은 노선 완행 trip 죽전→고색 = NORMAL")
        void localJukjeonToGosaek() {
            List<RailPattern.Stop> local = patternWhere(SD_D, p -> p.size() == 14
                    && "죽전(수인분당선)".equals(p.get(0).name()) && "고색".equals(p.get(p.size() - 1).name()));
            RoutingLeg leg = leg(SD_D, "수인분당선", "수인분당선<하행>", local, "죽전", "고색");

            assertThat(classifier.classify(leg)).isEqualTo(TrainKind.NORMAL);
        }
    }

    @Nested
    @DisplayName("그 밖의 철도·수단")
    class Others {

        @Test
        @DisplayName("ITX-청춘 노선 = ITX(급행 끄기 대상 아님)")
        void itx() {
            RoutingLeg leg = rail(RoutingMode.RAIL, "1:RR_ACC4_NATZ200_D", "[일반]ITX청춘(천마산.신내)", "하행");

            assertThat(classifier.classify(leg)).isEqualTo(TrainKind.ITX);
        }

        @Test
        @DisplayName("공항철도 = NORMAL(카탈로그 밖 노선)")
        void airportRailroad() {
            RoutingLeg leg = leg("1:RR_ACC1_S-1-AP-1D", "공항철도", "공항철도<하행>",
                    pattern("1:RR_ACC1_S-1-AP-1D", 14), null, null);

            assertThat(classifier.classify(leg)).isEqualTo(TrainKind.NORMAL);
        }

        @Test
        @DisplayName("이름에 직통 = DIRECT(안전장치 — 운영 그래프에는 없다)")
        void direct() {
            RoutingLeg leg = rail(RoutingMode.SUBWAY, "1:RR_ACC1_S-1-AP-9D", "공항철도", "공항철도(직통)<하행>");

            assertThat(classifier.classify(leg)).isEqualTo(TrainKind.DIRECT);
        }

        @Test
        @DisplayName("2호선·간선 철도 = NORMAL, 버스·도보 = null")
        void normalAndNonRail() {
            assertThat(classifier.classify(rail(RoutingMode.SUBWAY, "1:RR_ACC1_S-1-02-1I", "서울2호선",
                    "서울2호선(본선)<내선>"))).isEqualTo(TrainKind.NORMAL);
            assertThat(classifier.classify(rail(RoutingMode.RAIL, "1:RR_ACC4_KTX", "[KTX]경부", "하행")))
                    .isEqualTo(TrainKind.NORMAL);
            assertThat(classifier.classify(rail(RoutingMode.BUS, "1:BR_740", "740", "740"))).isNull();
            assertThat(classifier.classify(rail(RoutingMode.WALK, null, null, null))).isNull();
            assertThat(classifier.classify(null)).isNull();
        }
    }

    @Nested
    @DisplayName("패턴 캐시가 빈 때(부팅 직후·worker 첫 재탐색) — 노선 이름으로만")
    class ColdCache {

        private final RailServiceClassifier cold = SubwayExpressFixtures.coldRailServiceClassifier();

        @Test
        @DisplayName("급행 이름 노선은 EXPRESS, 정차 패턴형은 NORMAL(알 수 없어 일반으로 둔다)")
        void nameFallback() {
            assertThat(cold.classify(leg(L9_EXPRESS_D, "서울9호선", L9_EXPRESS_LONG, pattern(L9_EXPRESS_D, 16),
                    "가양", "염창"))).isEqualTo(TrainKind.EXPRESS);
            assertThat(cold.classify(leg(SD_D, "수인분당선", "수인분당선<하행>",
                    patternWhere(SD_D, p -> p.size() == 29 && !names(p).contains("보정")), "죽전", "고색")))
                    .isEqualTo(TrainKind.NORMAL);
        }
    }

    @Test
    @DisplayName("annotate — 모든 철도 구간에 종류를 붙이고 나머지 값은 보존한다")
    void annotate() {
        RoutingLeg express = leg(L9_EXPRESS_D, "서울9호선", L9_EXPRESS_LONG, pattern(L9_EXPRESS_D, 16), "가양", "염창");
        RoutingLeg walk = rail(RoutingMode.WALK, null, null, null);
        RoutingResult result = new RoutingResult(RoutingProvider.OTP, "v", "n", "g", null, null, T0, List.of(),
                List.of(new RoutingItinerary(T0, 0, 0, 0, 0, false, List.of(), Fare.unknown(),
                        List.of(walk, express), 4000)));

        RoutingResult annotated = classifier.annotate(result);

        RoutingLeg out = annotated.itineraries().get(0).legs().get(1);
        assertThat(out.trainKind()).isEqualTo(TrainKind.EXPRESS);
        assertThat(out.withTrainKind(null)).isEqualTo(express);
        assertThat(annotated.itineraries().get(0).legs().get(0).trainKind()).isNull();
        assertThat(annotated.itineraries().get(0).generalizedCost()).isEqualTo(4000);
    }

    @Test
    @DisplayName("노선형 급행 route id = 9호선 급행·1호선 경인·경부 급행(상·하행)")
    void expressRouteIds() {
        assertThat(classifier.expressRouteIds()).containsExactlyInAnyOrder(
                "1:RR_ACC1_S-1-09-2U", "1:RR_ACC1_S-1-09-2D", "1:RR_ACC1_S-1-01-4U", "1:RR_ACC1_S-1-01-4D",
                "1:RR_ACC1_S-1-01-6U", "1:RR_ACC1_S-1-01-6D");
    }

    @Test
    @DisplayName("expandAllStops — 9호선 급행 가양→염창을 완행으로 펼치고 서는 역만 trainStops")
    void expandAllStops() {
        List<RailPattern.Stop> stops = slice(pattern(L9_EXPRESS_D, 16), "가양", "염창");

        List<RailStop> all = classifier.expandAllStops("서울9호선", stops.stream()
                .map(s -> new PlannedStop(s.name(), s.lon(), s.lat())).toList()).orElseThrow();

        assertThat(all).extracting(RailStop::name).containsExactly("가양", "증미", "등촌", "염창");
        assertThat(all).extracting(RailStop::trainStops).containsExactly(true, false, false, true);
        assertThat(all.get(1).stationId()).isEqualTo(SubwayExpressFixtures.stationId("1009", "증미"));
        assertThat(all.get(1).lon()).isNotNull();
        assertThat(RailServiceClassifier.skipsAny(all)).isTrue();
    }

    @Test
    @DisplayName("expandAllStops — 서울 지하철 노선이 아니거나 정차가 2개 미만이면 empty")
    void expandUnknown() {
        assertThat(classifier.expandAllStops("부산1호선", List.of(new PlannedStop("a", 0, 0),
                new PlannedStop("b", 0, 0)))).isEmpty();
        assertThat(classifier.expandAllStops("서울9호선", List.of(new PlannedStop("가양", 126.85, 37.56)))).isEmpty();
    }

    // ── 도구 ─────────────────────────────────────────────────────────────

    private static List<RailPattern.Stop> pattern(String routeId, int size) {
        return patternWhere(routeId, p -> p.size() == size);
    }

    private static List<RailPattern.Stop> patternWhere(String routeId, Predicate<List<RailPattern.Stop>> test) {
        return SubwayExpressFixtures.routePatterns(routeId).stream().map(RailPattern::stops).filter(test)
                .findFirst().orElseThrow(() -> new IllegalArgumentException(routeId));
    }

    private static List<String> names(List<RailPattern.Stop> stops) {
        return stops.stream().map(RailPattern.Stop::name).toList();
    }

    /** 표시 이름(노선 괄호 뗀)으로 승차~하차를 자른다. 둘 다 null 이면 전체. */
    private static List<RailPattern.Stop> slice(List<RailPattern.Stop> stops, String board, String alight) {
        if (board == null) {
            return stops;
        }
        int from = -1;
        int to = -1;
        for (int i = 0; i < stops.size(); i++) {
            String name = SubwayPatternStationMatcher.displayName(stops.get(i).name());
            if (from < 0 && board.equals(name)) {
                from = i;
            } else if (from >= 0 && alight.equals(name)) {
                to = i;
                break;
            }
        }
        if (from < 0 || to < 0) {
            throw new IllegalArgumentException(board + "→" + alight + " " + names(stops));
        }
        return stops.subList(from, to + 1);
    }

    private static RoutingLeg leg(String routeId, String shortName, String longName, List<RailPattern.Stop> pattern,
                                  String board, String alight) {
        List<RailPattern.Stop> stops = slice(pattern, board, alight);
        List<StopOccurrence> occurrences = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            RailPattern.Stop s = stops.get(i);
            occurrences.add(new StopOccurrence("1:S" + i, null, null, i, s.lon(), s.lat(), s.name()));
        }
        return new RoutingLeg(RoutingMode.SUBWAY, "SUBWAY", "1", "1:A", routeId, shortName, null, "1:T",
                LocalDate.of(2026, 10, 21), T0, T0, 600, occurrences, List.of(), LegGeometry.missing("x"),
                Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), Set.of(), null, null, longName,
                "1:pattern::01", null);
    }

    private static RoutingLeg rail(RoutingMode mode, String routeId, String shortName, String longName) {
        return new RoutingLeg(mode, mode.name(), "1", "1:A", routeId, shortName, null, "1:T",
                LocalDate.of(2026, 10, 21), T0, T0, 600, List.of(), List.of(), LegGeometry.missing("x"),
                Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), Set.of(), null, null, longName, null, null);
    }
}
