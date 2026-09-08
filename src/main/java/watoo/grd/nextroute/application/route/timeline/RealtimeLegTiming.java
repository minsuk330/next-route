package watoo.grd.nextroute.application.route.timeline;

import watoo.grd.nextroute.application.route.routing.ActualSource;

import java.time.Instant;

/**
 * 한 대중교통 leg 의 실시간/ML 예측 시각. 승차 stop 출발과 하차 stop 도착을 모두 담아
 * 도착 지연이 다음 편 연결에 전파되게 한다(도착값을 출발 근사로만 쓰지 않음 — 둘 다 보존).
 *
 * @param realtimeDepartureAt 승차 stop 실제/예측 출발. 없으면 null → 정적 fallback
 * @param realtimeArrivalAt   하차 stop 실제/예측 도착. 없으면 출발+정적 승차소요로 근사
 * @param cancelled           운행 취소 확인. true 면 정적 fallback 금지(후보 제외)
 * @param observedAt          이 값을 관측/계산한 시각. freshness 판정에 사용
 */
public record RealtimeLegTiming(
        Instant realtimeDepartureAt,
        Instant realtimeArrivalAt,
        boolean cancelled,
        ActualSource source,
        Instant observedAt
) {
}
