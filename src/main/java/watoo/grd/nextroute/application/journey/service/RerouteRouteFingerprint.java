package watoo.grd.nextroute.application.journey.service;

import watoo.grd.nextroute.application.route.dto.LaneResult;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 후보 경로의 지문 — "같은 경로인가"를 가르는 값(#264 결정 1-가).
 *
 * <p>같은 자리에서 다시 찾은 경로가 <b>같은 경로</b>면 숫자만 고치고 candidateId 를 유지하고, 다른
 * 경로면 충분히 나을 때만 바꾼다. 그 판단의 기준이 "탈 것과 내릴 곳"이다 — 하차 자리 + 대중교통 구간마다
 * (첫 노선, 타는 곳, 내리는 곳). 소요·도착 시각은 넣지 않는다. 그 값은 매번 바뀌어도 같은 경로다.
 */
public final class RerouteRouteFingerprint {

    private RerouteRouteFingerprint() {
    }

    public static String of(String slotKey, PathResult path) {
        StringBuilder key = new StringBuilder(slotKey == null ? "" : slotKey);
        if (path != null && path.subPaths() != null) {
            for (SubPathResult sub : path.subPaths()) {
                if (sub.trafficType() != 1 && sub.trafficType() != 2) {
                    continue;
                }
                key.append('|').append(sub.trafficType()).append(':').append(routeOf(sub))
                        .append(':').append(sub.startName()).append('>').append(sub.endName());
            }
        }
        return sha1(key.toString());
    }

    private static String routeOf(SubPathResult sub) {
        if (sub.lanes() == null || sub.lanes().isEmpty()) {
            return "";
        }
        LaneResult lane = sub.lanes().get(0);
        if (lane.busLocalBlID() != null && !lane.busLocalBlID().isBlank()) {
            return lane.busLocalBlID();
        }
        if (lane.busNo() != null) {
            return lane.busNo();
        }
        return lane.subwayCode() == null ? String.valueOf(lane.name()) : String.valueOf(lane.subwayCode());
    }

    private static String sha1(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
