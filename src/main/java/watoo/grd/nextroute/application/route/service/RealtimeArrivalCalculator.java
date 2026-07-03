package watoo.grd.nextroute.application.route.service;

import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;

import java.time.Instant;
import java.util.Optional;

/**
 * 실시간 도착정보(BusArrivalInfo) → 사용자 도착시각({@code userAt}) 이후 최초 버스 도착 Instant.
 * enricher(검색 시)와 final check(재검증 시)가 공유한다.
 */
public final class RealtimeArrivalCalculator {

    private RealtimeArrivalCalculator() {}

    /**
     * dataTimestamp + sectionTime1/2 중 {@code userAt} 이후로 가장 이른 도착. 없으면 empty.
     */
    public static Optional<Instant> earliest(BusArrivalInfo ai, Instant userAt) {
        Optional<Instant> mkTm = BusTimeParser.parse(ai.dataTimestamp());
        if (mkTm.isEmpty()) return Optional.empty();

        Instant base = mkTm.get();
        Instant best = null;
        if (ai.sectionTime1() != null) {
            Instant t = base.plusSeconds(ai.sectionTime1());
            if (!t.isBefore(userAt) && (best == null || t.isBefore(best))) best = t;
        }
        if (ai.sectionTime2() != null) {
            Instant t = base.plusSeconds(ai.sectionTime2());
            if (!t.isBefore(userAt) && (best == null || t.isBefore(best))) best = t;
        }
        return Optional.ofNullable(best);
    }
}
