package watoo.grd.nextroute.application.subway.service;

import java.util.List;
import java.util.Optional;

/**
 * OTP 정차를 서울 실시간 역({@code statn_id})에 잇는다(#283). I/O 없음.
 *
 * <h2>좌표만으로 잇지 않는 이유</h2>
 *
 * <p>OTP 정차 좌표와 역 좌표의 기준이 노선마다 다르다(승강장 vs 역사 중심). 좌표 50m 최근접만
 * 쓰면 실측으로 노선별 10~26% 가 끊기고(9호선 10/38, 종로3가 291m), 더 나쁘게는 <b>틀린 역에
 * 붙는다</b> — 인천공항2터미널의 최근접은 870m 떨어진 공항화물청사였다.
 *
 * <p>그래서 같은 노선 안에서 <b>이름이 같으면 거리를 넉넉히(5km)</b> 보고 채택하고, 이름이 다를 때만
 * 좁은 좌표(150m)로 대체한다.
 * <ul>
 *   <li>이름을 먼저 믿는 이유: 좌표가 틀린 쪽이 실재한다. OTP feed 는 1호선 도봉·도봉산 좌표가
 *       서로 바뀌어 있고(약 1.1km), 공항철도 인천공항2터미널은 역 마스터와 약 3km 떨어져 있다.
 *       같은 노선에 같은 이름 역은 하나라 이름이 맞으면 같은 역이다. 5km 는 데이터 사고만 막는 한계다.</li>
 *   <li>이름이 다른 이유는 개명·표기 차이다(당고개→불암산, 화전→한국항공대, 응암↔응암순환(상선),
 *       4·19민주묘지↔4.19 민주묘지).</li>
 * </ul>
 */
public final class SubwayPatternStationMatcher {

    static final double SAME_NAME_RADIUS_M = 5_000.0;
    static final double NEAREST_RADIUS_M = 150.0;

    private static final DestinationNormalizer NORMALIZER = new DestinationNormalizer();

    private SubwayPatternStationMatcher() {
    }

    /** 같은 노선의 역 하나. */
    public record Candidate(String stationId, String stationName, double lat, double lon) {
    }

    /**
     * @param candidates 같은 line_id 의 역들
     * @return 이을 역. 이름·좌표 둘 다 맞지 않으면 빈 값 — 지어내지 않는다
     */
    public static Optional<Candidate> match(String otpName, double lat, double lon, List<Candidate> candidates) {
        String name = NORMALIZER.normalize(otpName);
        Candidate sameName = null;
        double sameNameDistance = Double.MAX_VALUE;
        Candidate nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Candidate candidate : candidates) {
            double distance = distanceMeters(lat, lon, candidate.lat(), candidate.lon());
            if (name != null && name.equals(NORMALIZER.normalize(candidate.stationName()))
                    && distance < sameNameDistance) {
                sameName = candidate;
                sameNameDistance = distance;
            }
            if (distance < nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        if (sameName != null && sameNameDistance <= SAME_NAME_RADIUS_M) {
            return Optional.of(sameName);
        }
        if (nearest != null && nearestDistance <= NEAREST_RADIUS_M) {
            return Optional.of(nearest);
        }
        return Optional.empty();
    }

    /** 표시용 이름. OTP 가 붙인 노선 괄호({@code 강남(2호선)})를 뗀다. */
    public static String displayName(String otpName) {
        return NORMALIZER.normalize(otpName);
    }

    static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }
}
