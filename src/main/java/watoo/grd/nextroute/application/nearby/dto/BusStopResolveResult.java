package watoo.grd.nextroute.application.nearby.dto;

import java.util.List;

/**
 * 네이버 지도 심벌을 내부 버스 정류장으로 연결한 결과다.
 *
 * 좌표만으로 반대편 정류장을 잘못 연결하지 않도록, 확정하지 못한 경우에는
 * 후보를 함께 반환한다.
 */
public record BusStopResolveResult(
        Status status,
        Candidate matchedStop,
        List<Candidate> candidates
) {
    public enum Status {
        MATCHED,
        AMBIGUOUS,
        NOT_FOUND
    }

    public record Candidate(
            String stopId,
            String stopName,
            String arsId,
            double latitude,
            double longitude,
            int distanceMeters
    ) {}

    public static BusStopResolveResult matched(Candidate stop) {
        return new BusStopResolveResult(Status.MATCHED, stop, List.of(stop));
    }

    public static BusStopResolveResult ambiguous(List<Candidate> candidates) {
        return new BusStopResolveResult(Status.AMBIGUOUS, null, List.copyOf(candidates));
    }

    public static BusStopResolveResult notFound() {
        return new BusStopResolveResult(Status.NOT_FOUND, null, List.of());
    }
}
