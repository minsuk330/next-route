package watoo.grd.nextroute.application.route.routing;

import java.time.Duration;
import java.time.Instant;

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
 */
public record AlternativeRoute(
        String routeId,
        String routeName,
        String routeColor,
        String alightStopId,
        Instant boardAt,
        Instant alightAt
) {
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
