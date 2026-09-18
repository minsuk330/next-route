package watoo.grd.nextroute.application.journey.dto;

import java.util.Objects;

/**
 * 재탐색이 시작되는 지점. 환승 대기 leg 뿐 아니라 진행 중인 탑승 leg 의 중간 정류장도
 * 가리킬 수 있다 — 옛 {@code transferIndex} 는 leg 번호만 가리켜 후자를 표현할 수 없었다
 * (journey-reroute-nextstop PLAN PR-B, de-transferIndex).
 *
 * <p><b>서버 파생이다.</b> 클라이언트는 좌표나 정류장 id 를 보내지 않는다 — 현재 진행 leg·
 * 정류장 목록에서 서버가 만든다. 클라 입력을 믿으면 사용자가 조작한 좌표로 엉뚱한 leg 를
 * 가리킬 수 있다.
 *
 * <p>정류장 식별은 PR-A 가 정한 것과 같다 — {@code sourceStopId} 단독이 아니라
 * {@code (tripId, sourceStopSequence, occurrenceIndex)} 조합이다(같은 정류장 루프 재방문 구분).
 * {@code sourceStopSequence} 는 OTP {@code StopCall} 스키마에 GTFS stop_sequence 가 없어 이 경로로는
 * 채워지지 않는 경우가 흔하다({@link watoo.grd.nextroute.application.route.routing.StopOccurrence}
 * 의 같은 경고 참고) — 실질 식별자는 {@code tripId + occurrenceIndex} 다.
 *
 * @param legIndex            이 원점이 속한 leg 의 인덱스(현재 revision 기준)
 * @param tripId              leg 의 trip 식별(OTP {@code trip.gtfsId})
 * @param sourceStopSequence  원천 GTFS stop_sequence(있으면). 이 경로로는 null 이 흔하다
 * @param occurrenceIndex     leg 안에서 몇 번째 방문인지(0-base) — 실질 식별자의 핵심
 * @param stopGtfsId          이 정류장 자신의 GTFS id(표시·실시간 조회용, 정체성 판정에는 안 씀)
 * @param kind                {@link RerouteOriginKind}
 */
public record RerouteOrigin(
        int legIndex,
        String tripId,
        Integer sourceStopSequence,
        int occurrenceIndex,
        String stopGtfsId,
        RerouteOriginKind kind
) {
    public RerouteOrigin {
        Objects.requireNonNull(tripId, "tripId");
        Objects.requireNonNull(stopGtfsId, "stopGtfsId");
        Objects.requireNonNull(kind, "kind");
    }
}
