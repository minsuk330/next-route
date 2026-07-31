package watoo.grd.nextroute.application.journey.service;

import org.springframework.stereotype.Component;

/**
 * 두 지점이 같은 정류장인지 판별한다.
 *
 * <p>내려서 바로 옆에서 갈아타는 환승과, 100m 걸어가야 하는 환승은 화면에도 알림에도 다르게
 * 나가야 한다. 그런데 검색 응답은 "같은 정류장"이라고 말해 주지 않는다. 식별자·이름·좌표를
 * 가지고 <b>추정</b>해야 한다.
 *
 * <p>그래서 결론을 셋으로 둔다 — 같다, 다르다, <b>모르겠다</b>. 근거가 약할 때 억지로 둘 중
 * 하나를 고르면 그 추측이 조용히 사실처럼 굳는다.
 *
 * <p>판별 우선순위는 근거가 강한 순이다.
 * <ol>
 *   <li>내부 정류장·역 식별자</li>
 *   <li>ARS 번호(정류장에 붙어 있는 다섯 자리 번호)</li>
 *   <li>정규화한 이름이 같고 좌표가 30m 안</li>
 *   <li>그 밖에는 판단하지 않는다</li>
 * </ol>
 */
@Component
public class StopIdentityResolver {

    /** 같은 정류장으로 볼 최대 거리(m). 정류장 하나의 승하차 지점 차이를 흡수할 정도. */
    static final double SAME_STOP_METERS = 30.0;

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    public Verdict resolve(StopRef a, StopRef b) {
        if (a == null || b == null) {
            return Verdict.UNKNOWN;
        }
        if (hasText(a.stopId()) && hasText(b.stopId())) {
            return a.stopId().equals(b.stopId()) ? Verdict.SAME : Verdict.DIFFERENT;
        }
        if (hasText(a.arsId()) && hasText(b.arsId())) {
            return a.arsId().equals(b.arsId()) ? Verdict.SAME : Verdict.DIFFERENT;
        }
        return byNameAndCoords(a, b);
    }

    /**
     * 이름과 좌표로 본다.
     *
     * <p>이름이 같아도 좌표가 멀면 같다고 하지 않는다. "시청" 같은 이름은 여러 곳에 있다.
     * 반대로 좌표가 멀다는 것만으로는 다르다고 말할 수 있다 — 30m 밖이면 걸어야 하니까.
     */
    private Verdict byNameAndCoords(StopRef a, StopRef b) {
        Double distance = distanceMeters(a, b);
        if (distance == null) {
            return Verdict.UNKNOWN;
        }
        if (distance > SAME_STOP_METERS) {
            return Verdict.DIFFERENT;
        }
        String nameA = normalize(a.name());
        String nameB = normalize(b.name());
        if (nameA == null || nameB == null) {
            return Verdict.UNKNOWN;
        }
        return nameA.equals(nameB) ? Verdict.SAME : Verdict.UNKNOWN;
    }

    private Double distanceMeters(StopRef a, StopRef b) {
        if (a.x() == null || a.y() == null || b.x() == null || b.y() == null) {
            return null;
        }
        double lat1 = Math.toRadians(a.y());
        double lat2 = Math.toRadians(b.y());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b.x() - a.x());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    /** 공백만 걷어낸다. 접미사("역", "정류장")까지 지우면 서로 다른 곳이 같아 보일 수 있다. */
    private String normalize(String name) {
        if (name == null) {
            return null;
        }
        String stripped = name.replaceAll("\\s+", "");
        return stripped.isEmpty() ? null : stripped;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 판별에 쓸 한 지점. 있는 값만 채우면 된다. */
    public record StopRef(String stopId, String arsId, String name, Double x, Double y) {
    }

    public enum Verdict {
        SAME,
        DIFFERENT,
        /** 근거가 부족하다. 같다고도 다르다고도 하지 않는다. */
        UNKNOWN
    }
}
