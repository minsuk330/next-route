package watoo.grd.nextroute.application.map.service;

import watoo.grd.nextroute.application.journey.service.TrackStopSiblings;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStop;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 지도 정류장 타일(#326)의 순수 규칙: 좌표 반올림 → 타일 key, 같은 정류장 합치기, 해시.
 *
 * <h2>key</h2>
 * {@code floor(lat*10)_floor(lng*10)} — 0.1°(약 11km) 칸. <b>반올림한 좌표로</b> 계산한다: 응답 좌표가 5자리로
 * 반올림되므로, 원좌표로 칸을 정하면 37.499996 이 서버는 374, 클라는 375 칸이 된다(Opus 리뷰). {@code /0.1} 이 아니라
 * {@code *10} 인 것도 계약이다 — {@code /0.1} 은 330~1320 경계에서 319곳이 틀린다.
 */
public final class BusStopTiles {

    private static final double COORDINATE_SCALE = 100_000d;

    private BusStopTiles() {
    }

    /** 소수 5자리(≈1.1m). v1 스냅샷과 같다. */
    public static double round(double value) {
        return Math.round(value * COORDINATE_SCALE) / COORDINATE_SCALE;
    }

    /**
     * 응답에 쓰는 숫자. Double.toString 은 JDK 버전마다 달라질 수 있어(해시가 통째로 바뀜) 정수에서 만든다. 뒤 0 은 떼되
     * 지수 표기({@code 1.3E+2})가 되지 않게 scale 을 0 아래로 내리지 않는다.
     */
    public static BigDecimal plain(double value) {
        BigDecimal stripped = BigDecimal.valueOf(Math.round(value * COORDINATE_SCALE)).movePointLeft(5)
                .stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    public static String key(double lat, double lon) {
        return (long) Math.floor(round(lat) * 10) + "_" + (long) Math.floor(round(lon) * 10);
    }

    /**
     * 피드별로 쪼개진 같은 정류장을 한 점으로({@link TrackStopSiblings}, 근처 목록과 같은 규칙): 대표(서울 → 경기 →
     * 인천)만 남기고, 대표에서 300m 밖 멤버는 따로 둔다. 묶이지 않는 정류장은 그대로.
     */
    public static List<TransitStop> mergeSiblings(List<TransitStop> stops) {
        Map<String, List<TransitStop>> byKey = new LinkedHashMap<>();
        List<TransitStop> result = new ArrayList<>();
        for (TransitStop stop : stops) {
            String key = TrackStopSiblings.groupKey(stop.gtfsId());
            if (key == null) {
                result.add(stop);
            } else {
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(stop);
            }
        }
        for (List<TransitStop> members : byKey.values()) {
            if (members.size() == 1) {
                result.add(members.get(0));
                continue;
            }
            List<TransitStop> sorted = new ArrayList<>(members);
            sorted.sort(Comparator.comparingInt((TransitStop s) -> TrackStopSiblings.representativeRank(s.gtfsId()))
                    .thenComparing(TransitStop::gtfsId));
            TransitStop representative = sorted.get(0);
            Set<String> merged = new HashSet<>(TrackStopSiblings.within(representative, sorted));
            result.add(representative);
            for (TransitStop member : sorted.subList(1, sorted.size())) {
                if (!merged.contains(member.gtfsId())) {
                    result.add(member);
                }
            }
        }
        return result;
    }

    /** key 순, 타일 안은 gtfsId 순 — 같은 데이터면 같은 바이트(해시 안정). */
    public static SortedMap<String, List<TransitStop>> byTile(List<TransitStop> stops) {
        SortedMap<String, List<TransitStop>> tiles = new TreeMap<>();
        for (TransitStop stop : stops) {
            tiles.computeIfAbsent(key(stop.lat(), stop.lon()), k -> new ArrayList<>()).add(stop);
        }
        tiles.values().forEach(list -> list.sort(Comparator.comparing(TransitStop::gtfsId)));
        return tiles;
    }

    /** SHA-256 hex 앞 12자. */
    public static String sha12(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 전체 버전: key 순 {@code key=tileVersion} 줄의 해시. */
    public static String manifestVersion(SortedMap<String, String> tileVersions) {
        StringBuilder sb = new StringBuilder();
        tileVersions.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        return sha12(sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
