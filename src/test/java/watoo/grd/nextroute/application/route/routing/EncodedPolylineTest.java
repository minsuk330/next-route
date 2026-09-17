package watoo.grd.nextroute.application.route.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.dto.CoordPoint;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B1 계약: fail-closed 디코더. 잘린 문자열/범위 밖 좌표는 예외 없이 빈 리스트,
 * 1점짜리 입력은 그대로 1점 반환(2점 미만 → null 판정은 호출부 책임, {@link LegacyRouteResponseMapperTest} 참조).
 */
class EncodedPolylineTest {

    // otp/planconnection-success.json 의 첫 BUS leg 형상(length=16, 롯데백화점 37.5645,126.982 → 고속터미널 37.5058,127.004)
    private static final String BUS_SHAPE =
            "cygdFod`fWfTfE??nPoK??rrB_X??pK???ls@_X??r]gE??jgBwj@??nKg^";

    @Test
    @DisplayName("실 OTP fixture 디코드: 점 개수는 legGeometry.length 와 일치, 첫/끝 좌표는 leg from/to 와 50m 이내")
    void decodesRealFixture() {
        List<CoordPoint> points = EncodedPolyline.decode(BUS_SHAPE);
        assertThat(points).hasSize(16);

        CoordPoint first = points.get(0);
        CoordPoint last = points.get(points.size() - 1);
        assertThat(haversineMeters(first.y(), first.x(), 37.5645, 126.982)).isLessThan(50.0);
        assertThat(haversineMeters(last.y(), last.x(), 37.5058, 127.004)).isLessThan(50.0);
    }

    @Test
    @DisplayName("null/빈 문자열 → 빈 리스트")
    void nullOrEmptyIsEmptyList() {
        assertThat(EncodedPolyline.decode(null)).isEmpty();
        assertThat(EncodedPolyline.decode("")).isEmpty();
    }

    @Test
    @DisplayName("중간 절단(잘린 varint) → 예외 없이 빈 리스트")
    void truncatedMidVarintIsEmptyNotException() {
        // 두 번째 좌표쌍(연속바이트, 상위비트=1)을 완전히 끊어 varint 미완성 상태로 만든다.
        String truncated = BUS_SHAPE.substring(0, 5);
        assertThat(EncodedPolyline.decode(truncated)).isEmpty();
    }

    @Test
    @DisplayName("범위를 벗어난 좌표(lat=91) → 예외 없이 빈 리스트")
    void outOfRangeCoordinateIsEmptyList() {
        assertThat(EncodedPolyline.decode("_mljP?")).isEmpty();
    }

    @Test
    @DisplayName("1점짜리 유효 입력은 그대로 1점(구글 표준 예시 38.5,-120.2)")
    void singlePointDecodesToOnePoint() {
        List<CoordPoint> points = EncodedPolyline.decode("_p~iF~ps|U");
        assertThat(points).hasSize(1);
        assertThat(points.get(0).y()).isEqualTo(38.5);
        assertThat(points.get(0).x()).isEqualTo(-120.2);
    }

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return r * c;
    }
}
