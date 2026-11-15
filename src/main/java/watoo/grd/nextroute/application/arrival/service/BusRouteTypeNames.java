package watoo.grd.nextroute.application.arrival.service;

import java.util.Map;

/**
 * 노선 유형 코드 → 사람 말. 공급자마다 코드표가 달라 출처별로 둔다(#577).
 *
 * <ul>
 *   <li>서울 {@code routeType}: 서울시 버스도착정보 API 명세의 노선 유형.</li>
 *   <li>경기 {@code routeTypeCd}: 경기도 버스노선정보 API 명세의 노선 유형(GBIS).</li>
 *   <li>TAGO 는 숫자 코드가 없고 {@code routetp} 글자를 그대로 쓴다.</li>
 * </ul>
 */
final class BusRouteTypeNames {

    static final String SOURCE_SEOUL = "SEOUL";
    static final String SOURCE_GYEONGGI = "GG";
    static final String SOURCE_TAGO = "TAGO";

    /** 15 = 심야(N 버스 N61·N64·N75 가 15 로 온다, 2026-11-16 실응답). */
    private static final Map<Integer, String> SEOUL = Map.ofEntries(
            Map.entry(1, "공항"), Map.entry(2, "마을"), Map.entry(3, "간선"), Map.entry(4, "지선"),
            Map.entry(5, "순환"), Map.entry(6, "광역"), Map.entry(7, "인천"), Map.entry(8, "경기"),
            Map.entry(9, "폐지"), Map.entry(0, "공용"), Map.entry(15, "심야"));

    private static final Map<Integer, String> GYEONGGI = Map.ofEntries(
            Map.entry(11, "직행좌석"), Map.entry(12, "좌석"), Map.entry(13, "일반"),
            Map.entry(14, "광역급행"), Map.entry(15, "따복"), Map.entry(16, "경기순환"),
            Map.entry(21, "농어촌직행좌석"), Map.entry(22, "농어촌좌석"), Map.entry(23, "농어촌일반"),
            Map.entry(30, "마을"),
            Map.entry(41, "고속시외"), Map.entry(42, "좌석시외"), Map.entry(43, "일반시외"),
            Map.entry(51, "리무진공항"), Map.entry(52, "좌석공항"), Map.entry(53, "일반공항"));

    private BusRouteTypeNames() {
    }

    static String seoul(Integer code) {
        return code == null ? null : SEOUL.get(code);
    }

    static String gyeonggi(Integer code) {
        return code == null ? null : GYEONGGI.get(code);
    }
}
