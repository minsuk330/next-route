package watoo.grd.nextroute.application.journey.dto;

/**
 * 링 도착 예정({@code estimatedNextStationAt}·{@code estimatedAlightingAt})의 출처. 예정이 없으면 null 이다.
 *
 * <p>앱은 {@link #TYPICAL} 이면 도착 시각을 숨기고 링만 그린다 — 추정을 실시간처럼 보이지 않게 하려는 것이다.
 *
 * <p><b>배포 뒤 값을 더하지 않는다.</b> Redis 에 담긴 링을 롤백한 옛 코드가 읽다 모르는 값을 만나면 역직렬화가 실패하고
 * 링이 0 에서 다시 시작한다. 새 뜻이 필요하면 새 필드로 둔다.
 */
public enum SubwayRingEtaSource {
    /** 도착 API {@code barvlDt}(운행 중 남은 초). */
    LIVE,
    /** 오늘 앞 열차들의 실측 구간 시간 중앙값(#316). */
    MEASURED,
    /** GTFS 계획 시간 × 여유 배수. 실시간 근거가 없을 때 링을 움직이기 위한 추정. */
    TYPICAL
}
