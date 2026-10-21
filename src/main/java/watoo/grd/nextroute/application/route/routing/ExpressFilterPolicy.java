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
 *   <li><b>DIRECT 는 옵션과 무관하게 언제나 뺀다</b>(D3 공항철도 직통은 경로에 넣지 않는다. 운영 그래프에는 없다 — 안전장치)</li>
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
