package watoo.grd.nextroute.application.route.timeline;

import java.time.Instant;
import java.util.List;

/**
 * ETA 합성 결과. 하나의 시간축 위 WAIT/RIDE/WALK 구간 + 보강 후 도착/총시간. 정적/실시간 시각을 모두 보존한다.
 *
 * @param effectiveArrivalAt   보강 후 최종 도착(불가 시 null)
 * @param staticArrivalAt      보강 전 정적 도착(항상 보존)
 * @param effectiveTotalSeconds 최종도착-요청출발(초). 실시간 대기를 다시 더하지 않는다. 불가 시 -1
 * @param effectiveTotalMinutes 표시용 분(마지막에 반올림). 불가 시 -1
 * @param initialWaitSeconds   첫 승차까지의 대기(초). 총시간에 포함
 * @param feasibility          FEASIBLE/MISSED/CANCELLED/NO_CATCHABLE
 * @param exclusionReason      불가 사유(가능하면 null)
 * @param reReadyAt            불가 시 재검색 앵커 준비시각(MISSED/CANCELLED)
 * @param reFromLegIndex       불가 시 재검색 시작 leg 인덱스(가능하면 -1)
 */
public record ComposedTimeline(
        Instant requestedDepartureAt,
        Instant effectiveArrivalAt,
        Instant staticArrivalAt,
        int effectiveTotalSeconds,
        int effectiveTotalMinutes,
        int initialWaitSeconds,
        boolean feasible,
        Feasibility feasibility,
        String exclusionReason,
        List<TimelineSegment> segments,
        Instant reReadyAt,
        int reFromLegIndex
) {
    public ComposedTimeline {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }
}
