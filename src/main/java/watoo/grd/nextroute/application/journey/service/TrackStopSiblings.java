package watoo.grd.nextroute.application.journey.service;

import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStop;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 피드별로 쪼개진 같은 버스 정류장(형제). 순수 함수다.
 *
 * <h2>왜 필요한가</h2>
 * 수도권 GTFS 는 서울(SEB)·경기(GGB)·인천(ICB) 피드가 같은 실제 정류장을 각자의 stop 으로 둔다. 신분당선강남역은
 * {@code 1:BS_TAGO_SEB121000009}(서울 노선 20) / {@code GGB121000009}(경기 35) / {@code ICB121000009}(인천 1) 이다.
 * 하나만 보면 다른 피드 노선이 통째로 빠진다. 정류장 자체는 묶지 않는다(지도·근처 목록은 그대로) — 노선 목록만 합친다.
 *
 * <h2>규칙 (2026-10-09 운영 OTP 21만 stop 실측)</h2>
 * <ul>
 *   <li>세 피드 사이에서 접두사 뒤 숫자가 같으면 같은 정류장이다. 3,983묶음, 거리 p50 22m·p99 127m.</li>
 *   <li>그래도 1건은 1,346m 떨어진 딴 정류장이었다 → {@link #MAX_DISTANCE_METERS} 안만 형제로 본다.</li>
 *   <li>지방 피드는 숫자가 같아도 수십 km 떨어진 딴 정류장이다(ADB/TSB {@code 354000001}) → 수도권 세 피드만.</li>
 * </ul>
 */
public final class TrackStopSiblings {

    static final double MAX_DISTANCE_METERS = 300;
    private static final List<String> FEEDS = List.of("SEB", "GGB", "ICB");
    private static final Pattern METRO_BUS_STOP = Pattern.compile("^(\\d+:BS_TAGO_)(SEB|GGB|ICB)(\\d+)$");
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private TrackStopSiblings() {
    }

    /** 형제일 수 있는 다른 피드 id. 수도권 버스 정류장이 아니면 빈 목록. 있는지는 모른다(OTP 로 확인). */
    public static List<String> candidates(String gtfsStopId) {
        if (gtfsStopId == null) {
            return List.of();
        }
        Matcher m = METRO_BUS_STOP.matcher(gtfsStopId);
        if (!m.matches()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (String feed : FEEDS) {
            if (!feed.equals(m.group(2))) {
                ids.add(m.group(1) + feed + m.group(3));
            }
        }
        return ids;
    }

    /** 같은 정류장 묶음 키(접두사 + 뒤 숫자). 수도권 버스 정류장이 아니면 null — 묶지 않는다. */
    public static String groupKey(String gtfsStopId) {
        if (gtfsStopId == null) {
            return null;
        }
        Matcher m = METRO_BUS_STOP.matcher(gtfsStopId);
        return m.matches() ? m.group(1) + m.group(3) : null;
    }

    /**
     * 묶음 대표 우선순위. 작을수록 먼저: 서울 → 경기 → 인천. 서울을 앞세우는 이유는 지도 스냅샷(서울 DB)·서울 도착 API 와
     * 같은 정류장·이름·좌표가 되기 때문이다(경기 쪽엔 옛 이름도 있다: 당고개역 → 서울은 불암산역).
     */
    public static int representativeRank(String gtfsStopId) {
        Matcher m = gtfsStopId == null ? null : METRO_BUS_STOP.matcher(gtfsStopId);
        return m != null && m.matches() ? FEEDS.indexOf(m.group(2)) : FEEDS.size();
    }

    /** {@code stops} 중 {@code origin} 이 아니면서 {@link #MAX_DISTANCE_METERS} 안인 것의 id. 순서 유지. */
    public static List<String> within(TransitStop origin, List<TransitStop> stops) {
        List<String> ids = new ArrayList<>();
        for (TransitStop stop : stops) {
            if (!stop.gtfsId().equals(origin.gtfsId())
                    && distanceMeters(origin.lat(), origin.lon(), stop.lat(), stop.lon()) <= MAX_DISTANCE_METERS) {
                ids.add(stop.gtfsId());
            }
        }
        return ids;
    }

    /** 수백 m 라 등장방형 근사로 충분하다. */
    static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double y = Math.toRadians(lat2 - lat1);
        return Math.sqrt(x * x + y * y) * EARTH_RADIUS_METERS;
    }
}
