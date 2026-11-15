package watoo.grd.nextroute.application.route.routing;

import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.dto.TrainKind;

import java.util.List;
import java.util.Set;

/**
 * 급행 끄기 정책(subway-express PR-S2, D2·D3·D10). 본 검색·추가 검색·재탐색이 <b>같은 순서</b>로 쓴다 — 재탐색(S5)도 이 객체를
 * 그대로 부른다.
 *
 * <ol>
 *   <li><b>노선형 급행</b>(9호선 급행·1호선 경인/경부 급행 route)은 OTP 가 탐색 단계에서 거른다 — {@link #routeExclusions}</li>
 *   <li><b>정차 패턴형 급행</b>(수인분당·경의중앙 등 같은 노선 안 급행 trip)은 OTP 가 노선 단위로만 거를 수 있어 결과에서
 *       EXPRESS 구간이 든 경로를 뺀다 — {@link #filter}</li>
 *   <li><b>DIRECT 는 옵션과 무관하게 언제나 뺀다</b>(D3 공항철도 직통은 경로에 넣지 않는다). 직통 route
 *       ({@code [일반]서울공-인천국}, #495)는 OTP 어댑터가 탐색에서 먼저 빼고, 이 필터는 그래도 섞인 것을 거르는 안전장치다</li>
 * </ol>
 * 사후 필터로 0건이 될 때의 처리(D10 — 일반 대안 다시 묻기, 그래도 0건이면 필터 해제)는 부르는 쪽(검색 흐름)이 한다.
 * ITX 는 급행 끄기 대상이 아니다(D4).
 */
@Component
public class ExpressFilterPolicy {

    private final RailServiceClassifier classifier;

    public ExpressFilterPolicy(RailServiceClassifier classifier) {
        this.classifier = classifier;
    }

    /** OTP 탐색에서 뺄 route id. 급행 끄기면 노선형 급행 route, 아니면 없음. */
    public Set<String> routeExclusions(boolean excludeExpress) {
        return excludeExpress ? classifier.expressRouteIds() : Set.of();
    }

    /** 노선형 급행 route id(일반 대안 되찾기·D10 다시 묻기가 쓴다). */
    public Set<String> expressRouteIds() {
        return classifier.expressRouteIds();
    }

    /** 노선 이름의 모든 route id(지하철 대안 검색용). 모르면 빈 집합. */
    public Set<String> routeIdsOfLine(String shortName) {
        return classifier.routeIdsOfLine(shortName);
    }

    /**
     * 예매 열차(KTX·SRT·무궁화 등 — 원천 노선 이름이 {@code [고속]}·{@code [일반]} 으로 시작하는 국가철도)를 minRideMinutes 보다
     * 짧게 타는 구간이 든 경로를 뺀다(otp-kakao-parity). 2026-11-16 운영: 동탄→강남에 SRT 17분, 부평→서울에 KTX 한 구간이 섞였다.
     * ITX-청춘은 열차 종류로 따로 보여 주는 대상이라 남긴다. 장거리(40분 이상) 이용은 그대로 둔다.
     */
    public static List<RoutingItinerary> withoutShortIntercityHops(List<RoutingItinerary> itineraries,
                                                                  int minRideMinutes) {
        if (minRideMinutes <= 0) {
            return itineraries;
        }
        return itineraries.stream()
                .filter(it -> it.legs().stream().noneMatch(leg -> isShortIntercityHop(leg, minRideMinutes)))
                .toList();
    }

    static boolean isShortIntercityHop(RoutingLeg leg, int minRideMinutes) {
        String name = leg.sourceRouteName();
        return leg.mode() == RoutingMode.RAIL && leg.trainKind() != TrainKind.ITX && name != null
                && (name.startsWith("[고속]") || name.startsWith("[일반]"))
                && leg.rideDurationSeconds() < minRideMinutes * 60;
    }

    /** 사후 필터. DIRECT 구간이 든 경로는 언제나, 급행 끄기면 EXPRESS 구간이 든 경로도 뺀다. 순서는 지킨다. */
    public List<RoutingItinerary> filter(List<RoutingItinerary> itineraries, boolean excludeExpress) {
        return itineraries.stream()
                .filter(it -> !has(it, TrainKind.DIRECT))
                .filter(it -> !excludeExpress || !has(it, TrainKind.EXPRESS))
                .toList();
    }

    public RoutingResult filter(RoutingResult result, boolean excludeExpress) {
        return result.withItineraries(filter(result.itineraries(), excludeExpress));
    }

    /** 경로에 이 종류 구간이 있는가. */
    public static boolean has(RoutingItinerary itinerary, TrainKind kind) {
        return itinerary.legs().stream().anyMatch(leg -> leg.trainKind() == kind);
    }
}
