package watoo.grd.nextroute.application.journey.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Objects;

/**
 * 재탐색이 시작되는 지점. 환승 대기 leg 뿐 아니라 진행 중인 탑승 leg 의 중간 정류장도
 * 가리킬 수 있다 — 옛 {@code transferIndex} 는 leg 번호만 가리켜 후자를 표현할 수 없었다
 * (journey-reroute-nextstop PLAN PR-B, de-transferIndex).
 *
 * <p><b>서버 파생이다.</b> 클라이언트는 좌표나 정류장 id 를 보내지 않는다 — 현재 진행 leg 와
 * 노선 정류장 목록에서 서버가 만든다. 클라 입력을 믿으면 사용자가 조작한 값으로 엉뚱한 leg 를
 * 가리킬 수 있다.
 *
 * <h2>주소 체계는 서울 좌표계다 (OTP 아님)</h2>
 * <p>원점은 <b>진행 중인 여정</b>의 한 지점이고, 그 세계의 데이터는 전부 서울 id 로 돈다 —
 * 여정 leg({@code JourneyLeg.routeId}/{@code startStopId}), 노선 정류장 목록
 * ({@code BusRouteStopRepository.findStopsByRouteId} 가 seq 순으로 준다), 그리고 결정적으로
 * <b>실시간 캐시 키</b>({@code getArrivals(stopId, routeId, seq)})가 그렇다.
 *
 * <p>그래서 식별을 OTP 좌표계({@code gtfsId + occurrenceIndex})로 두면 조회할 때마다 번역이
 * 필요한데, 그 번역이 반만 된다 — {@code OtpSeoulIdentityResolver} 가 stop/route id 는 주지만
 * <b>seq 는 못 주고</b>({@link watoo.grd.nextroute.application.route.routing.StopOccurrence} 의
 * occurrenceIndex 를 서울 seq 로 간주하는 것은 그 javadoc 이 금지한다), trip 은 서울 쪽에 대응물도
 * 여정 쪽 저장도 없다. 애초에 원점 데이터가 서울 DB 에서 태어나므로 왕복 자체가 불필요하다.
 *
 * <p>OTP 좌표계는 <b>검색 결과 안에서만</b> 산다(PR-A 의 {@code StopOccurrence}·
 * {@code TruncatedRide}). 두 세계를 섞지 말 것.
 *
 * <h2>실시간이 없으면 후보가 아니다</h2>
 * <p>{@link #isRealtimeAddressable()} 셋({@code stopId}·{@code routeId}·{@code seq})이 다 있어야
 * 실시간 도착을 조회할 수 있다. 하나라도 없으면 그 원점은 <b>제안 후보에서 뺀다</b> — 시간표로
 * 폴백하지 않는다(사용자 결정). 하차는 되돌릴 수 없는 행동이고 사용자는 서버 제안을 믿고 몸을
 * 움직이기 때문이다. 지하철·외곽처럼 서울 버스 DB 로 못 푸는 지점은 여기서 자연히 걸러진다.
 *
 * @param legIndex 이 원점이 속한 leg 의 인덱스(현재 revision 기준)
 * @param stopId   서울 정류장 id. 원점을 만들었다면 이건 언제나 있다
 * @param routeId  서울 노선 id(그 leg 에서 타는/타고 있는 노선). 못 풀면 null
 * @param seq      그 노선 안에서의 정류장 순번. 같은 정류장 재방문(순환노선)을 가르는 값이기도
 *                 하다 — 실시간 캐시 키가 stopId 와 seq 를 함께 받는 이유가 그것이다. 못 풀면 null
 * @param kind     {@link RerouteOriginKind}
 */
public record RerouteOrigin(
        int legIndex,
        String stopId,
        String routeId,
        Integer seq,
        RerouteOriginKind kind
) {
    public RerouteOrigin {
        Objects.requireNonNull(stopId, "stopId");
        Objects.requireNonNull(kind, "kind");
    }

    /**
     * 실시간 도착을 조회할 수 있는 주소인가. 아니면 제안 후보가 될 수 없다(위 설명).
     *
     * <p>{@code @JsonIgnore} 인 이유 — {@code isX()} 를 Jackson 이 속성으로 오해해 봉투에 없는 칸을
     * 만들고, 그러면 저장된 origin 의 왕복이 깨진다({@link RerouteCandidateEnvelope} 의 같은 주석).
     */
    @JsonIgnore
    public boolean isRealtimeAddressable() {
        return routeId != null && seq != null;
    }
}
