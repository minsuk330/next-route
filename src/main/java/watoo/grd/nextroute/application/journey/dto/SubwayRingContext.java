package watoo.grd.nextroute.application.journey.dto;

/**
 * 링 한 건이 어느 고정 세대의 것인지와, 그 단계의 계획 역 체인.
 *
 * <p>fencing 축이 넷인 이유는 재탐색 때문이다. 개정본이 오르면 같은 {@code legIndex} 의 단계가
 * 복제되고, 계승은 {@code bindingVersion} 을 0 으로 되돌린다. 둘 중 하나만 보면 <b>옛 worker 가
 * 새 링을 덮는다</b>.
 *
 * <p>{@code startSequence} 는 직전 링이 없을 때 처음 향할 역이다. 보통은 승차 다음 역(1)이다 — 이동
 * 시작 관측으로 고정됐으면 열차는 이미 승차역을 떠났다. 사용자가 역에서 고르고 아직 안 움직였으면
 * (#304) 열차가 승차역에 오기 전일 수 있어 <b>승차역(0)</b>에서 시작한다.
 */
public record SubwayRingContext(
        Long journeyId,
        int routeRevision,
        int legIndex,
        int bindingVersion,
        SubwayRingChain chain,
        int startSequence,
        java.util.function.IntFunction<Typical> typical
) {

    public SubwayRingContext(Long journeyId, int routeRevision, int legIndex, int bindingVersion,
                             SubwayRingChain chain, int startSequence) {
        this(journeyId, routeRevision, legIndex, bindingVersion, chain, startSequence, null);
    }

    public SubwayRingContext(Long journeyId, int routeRevision, int legIndex, int bindingVersion,
                             SubwayRingChain chain) {
        this(journeyId, routeRevision, legIndex, bindingVersion, chain, 1);
    }

    /**
     * 구간 전형 시간 조회를 붙인다. 계산기는 GTFS 를 직접 읽지 않는다 — 갱신기가 순번 → 시간 함수를 넘긴다. 구간 전환 때 다음
     * 구간 값이 필요해서 목표 구간 하나가 아니라 함수다.
     */
    public SubwayRingContext withTypical(java.util.function.IntFunction<Typical> lookup) {
        return new SubwayRingContext(journeyId, routeRevision, legIndex, bindingVersion, chain, startSequence, lookup);
    }

    /** {@code sequence} 역으로 들어가는 구간(직전 역 → 그 역)의 전형 시간. 모르면 null. */
    public Typical typicalFor(int sequence) {
        return typical == null ? null : typical.apply(sequence);
    }

    /**
     * 목표 구간(직전 역 → 다음 역)의 GTFS 계획 시간. 갱신기가 <b>같은 패턴</b>에서 뽑는다 — 정차와 운행을 다른 패턴에서
     * 섞으면 차가 음수가 될 수 있다.
     *
     * @param dwellSeconds 직전 역 도착 → 출발. 모르거나 0 이하면 null
     * @param runSeconds   직전 역 출발 → 다음 역 도착. 모르면 null
     */
    public record Typical(Integer dwellSeconds, Integer runSeconds) {
    }
}
