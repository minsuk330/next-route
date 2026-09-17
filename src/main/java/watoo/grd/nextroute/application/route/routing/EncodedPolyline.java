package watoo.grd.nextroute.application.route.routing;

import watoo.grd.nextroute.application.route.dto.CoordPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Google encoded polyline(precision 5) 디코더. OTP {@code legGeometry.points} 전용(위도·경도 순서로 인코딩됨).
 *
 * <p><b>fail-closed 계약</b>: 절대 예외를 던지지 않는다. 잘린 문자열, 깨진 varint, 범위를 벗어난 좌표는
 * 전부 빈 리스트로 반환한다 — 형상은 부가정보지 검색 실패 사유가 아니다. 호출부는 결과가 2점 미만이면
 * {@code polyline=null} 로 처리한다(선은 2점부터 의미가 있음).
 */
public final class EncodedPolyline {

    private static final double PRECISION = 1e5;
    private static final double MAX_LAT = 90.0;
    private static final double MAX_LNG = 180.0;

    private EncodedPolyline() {
    }

    public static List<CoordPoint> decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return List.of();
        }
        try {
            return decodeInternal(encoded);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static List<CoordPoint> decodeInternal(String encoded) {
        List<CoordPoint> points = new ArrayList<>();
        int[] cursor = {0};
        int lat = 0;
        int lng = 0;
        int len = encoded.length();
        while (cursor[0] < len) {
            lat += readSignedDelta(encoded, cursor);
            lng += readSignedDelta(encoded, cursor);
            double latitude = lat / PRECISION;
            double longitude = lng / PRECISION;
            if (Math.abs(latitude) > MAX_LAT || Math.abs(longitude) > MAX_LNG) {
                return List.of();
            }
            points.add(new CoordPoint(longitude, latitude));
        }
        return List.copyOf(points);
    }

    private static int readSignedDelta(String encoded, int[] cursor) {
        int shift = 0;
        int result = 0;
        int b;
        do {
            b = encoded.charAt(cursor[0]++) - 63;
            result |= (b & 0x1f) << shift;
            shift += 5;
        } while (b >= 0x20);
        return (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
    }
}
