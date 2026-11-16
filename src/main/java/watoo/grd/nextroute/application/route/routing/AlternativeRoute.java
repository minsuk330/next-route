package watoo.grd.nextroute.application.route.routing;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 같은 정류장에서 타서 같은 정류장에 내리는 다른 버스 노선의 한 편(otp-bus-lane-merge).
 *
 * <p>출처는 둘이다. OTP {@code Leg.nextLegs}(같은 승차 정류장의 다음 편들)와, 같은 승하차 정류장을 지나는 다른
 * 후보 경로의 버스 구간. 어느 쪽이든 시각은 정적 시간표 값이다.
 *
 * @param routeId      원천 노선 id(OTP {@code route.gtfsId})
 * @param routeName    노선 번호(OTP {@code route.shortName})
 * @param routeColor   원천 노선 색("#" 없는 6자리 hex). 없으면 null
 * @param alightStopId 하차 정류장 원천 id. 모르면 null
 * @param boardAt      정적 승차 시각. 모르면 null
 * @param alightAt     정적 하차 시각. 모르면 null
 * @param stopIds      승차부터 하차까지 서는 정류장 원천 id(순서대로). 모르면 빈 목록 — 정류장이 같은 노선만 묶으므로
 *                     빈 목록은 묶이지 않는다
 */
public record AlternativeRoute(
        String routeId,
        String routeName,
        String routeColor,
        String alightStopId,
        Instant boardAt,
        Instant alightAt,
        List<String> stopIds
) {
    public AlternativeRoute {
        stopIds = stopIds == null ? List.of() : List.copyOf(stopIds);
    }

    /** 정류장 목록을 모르는 호출부용. */
    public AlternativeRoute(String routeId, String routeName, String routeColor, String alightStopId,
                            Instant boardAt, Instant alightAt) {
        this(routeId, routeName, routeColor, alightStopId, boardAt, alightAt, List.of());
    }

    /** 탑승 소요(초). 시각을 모르면 null. */
    public Long rideSeconds() {
        if (boardAt == null || alightAt == null) {
            return null;
        }
        return Duration.between(boardAt, alightAt).getSeconds();
    }

    /** 사용자에게 보이는 노선 이름. 번호가 없으면 id. */
    public String label() {
        return routeName != null ? routeName : routeId;
    }
}
