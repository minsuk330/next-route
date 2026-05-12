package watoo.grd.nextroute.application.route.service;

import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.dto.SubPathResult;

/**
 * 도보(trafficType=3) subPath의 시작/끝 좌표·출구·이름을 결정한다.
 *
 * 인접 대중교통 구간의 종류에 따라 다른 규칙 적용:
 *  - 지하철(trafficType=1): 출구 좌표가 둘 다 있으면 출구 우선, 부분 누락이면 X/Y 폴백
 *  - 버스(trafficType=2): X/Y 그대로
 *  - 없음(첫/마지막 도보): 사용자 요청 좌표
 *
 * 이름 우선순위:
 *  - 출구명("역명 11번 출구") > 역명/정류장명 > 사용자 입력명 > 더미("출발"/"도착")
 */
@Component
public class WalkCoordResolver {

    private static final int SUBWAY = 1;
    private static final int BUS = 2;

    /**
     * 도보의 시작점을 결정한다 (이전 subPath의 도착점이 도보의 시작).
     *
     * @param prev 도보 직전 subPath. null이면 도보가 첫 구간 = 출발지.
     */
    public Resolved resolveStart(SubPathResult prev,
                                 double requestedX, double requestedY,
                                 String requestedName) {
        if (prev == null) {
            return new Resolved(requestedX, requestedY, null, fallbackName(requestedName, "출발"));
        }

        if (prev.trafficType() == SUBWAY) {
            Double exitX = prev.endExitX();
            Double exitY = prev.endExitY();
            String exitNo = prev.endExitNo();
            if (exitX != null && exitY != null) {
                return new Resolved(exitX, exitY, exitNo,
                        composeStationExitName(prev.endName(), exitNo));
            }
            // 부분 누락 또는 출구 정보 없음 → endX/Y 폴백
            return new Resolved(
                    nullSafe(prev.endX(), requestedX),
                    nullSafe(prev.endY(), requestedY),
                    null,
                    fallbackName(prev.endName(), requestedName)
            );
        }

        if (prev.trafficType() == BUS) {
            return new Resolved(
                    nullSafe(prev.endX(), requestedX),
                    nullSafe(prev.endY(), requestedY),
                    null,
                    fallbackName(prev.endName(), requestedName)
            );
        }

        // 도보→도보 이상한 경우 — 요청 좌표 사용
        return new Resolved(requestedX, requestedY, null, fallbackName(requestedName, "출발"));
    }

    /**
     * 도보의 끝점을 결정한다 (다음 subPath의 시작점이 도보의 끝).
     *
     * @param next 도보 직후 subPath. null이면 도보가 마지막 구간 = 도착지.
     */
    public Resolved resolveEnd(SubPathResult next,
                               double requestedX, double requestedY,
                               String requestedName) {
        if (next == null) {
            return new Resolved(requestedX, requestedY, null, fallbackName(requestedName, "도착"));
        }

        if (next.trafficType() == SUBWAY) {
            Double exitX = next.startExitX();
            Double exitY = next.startExitY();
            String exitNo = next.startExitNo();
            if (exitX != null && exitY != null) {
                return new Resolved(exitX, exitY, exitNo,
                        composeStationExitName(next.startName(), exitNo));
            }
            return new Resolved(
                    nullSafe(next.startX(), requestedX),
                    nullSafe(next.startY(), requestedY),
                    null,
                    fallbackName(next.startName(), requestedName)
            );
        }

        if (next.trafficType() == BUS) {
            return new Resolved(
                    nullSafe(next.startX(), requestedX),
                    nullSafe(next.startY(), requestedY),
                    null,
                    fallbackName(next.startName(), requestedName)
            );
        }

        return new Resolved(requestedX, requestedY, null, fallbackName(requestedName, "도착"));
    }

    private double nullSafe(Double value, double fallback) {
        return value != null ? value : fallback;
    }

    private String composeStationExitName(String stationName, String exitNo) {
        if (isBlank(stationName)) return stationName != null ? stationName : "";
        if (isBlank(exitNo)) return stationName;
        return stationName + " " + exitNo + "번 출구";
    }

    private String fallbackName(String... candidates) {
        for (String c : candidates) {
            if (!isBlank(c)) return c;
        }
        return candidates.length > 0 ? candidates[candidates.length - 1] : "";
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public record Resolved(double x, double y, String exitNo, String name) {}
}
