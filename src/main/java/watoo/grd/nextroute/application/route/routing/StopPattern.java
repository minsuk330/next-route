package watoo.grd.nextroute.application.route.routing;

import java.util.List;

/**
 * OTP 정류장 하나를 지나는 노선 pattern 하나(national-otp P2-F PR4b 재료).
 *
 * <p>한 노선의 한 방향·variant 를 뜻한다. OTP 는 정차 순서가 다르면 별개 pattern 으로 쪼개므로,
 * 왕복 노선은 두 pattern(상행·하행, {@code stopGtfsIds} 순서 반대)으로 나온다.
 *
 * <p><b>{@code directionId} 는 신뢰하지 않는다.</b> KTDB GTFS {@code trips.txt} 에 {@code direction_id}
 * 컬럼이 없어 OTP 가 전 노선 -1(방향 무의미)을 준다. 방향 판정은 {@code stopGtfsIds} 순서(승차 index &lt;
 * 하차 index)로만 한다 — 소비처(PR4)의 책임이고, 이 어댑터는 원천값을 그대로 옮긴다.
 *
 * @param routeGtfsId    OTP route id (예 {@code 1:BR_TAGO_SEB100100022}). 서울 매핑은 소비처가 한다
 * @param routeShortName OTP shortName(버스번호 등)
 * @param directionId    OTP directionId. KTDB 는 -1(위 참조). null 가능
 * @param stopGtfsIds    이 pattern 의 정차 정류장을 <b>운행 순서대로</b>
 */
public record StopPattern(String routeGtfsId, String routeShortName, Integer directionId,
                          List<String> stopGtfsIds) {

    public StopPattern {
        stopGtfsIds = stopGtfsIds == null ? List.of() : List.copyOf(stopGtfsIds);
    }
}
