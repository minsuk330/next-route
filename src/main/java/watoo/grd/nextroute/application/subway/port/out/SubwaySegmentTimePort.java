package watoo.grd.nextroute.application.subway.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 지하철 한 구간(전역 출발 → 그 역 도착)의 <b>오늘 실측</b> 소요시간.
 *
 * <p>진행 링의 도착 예정을 도착 API 남은 초({@code barvlDt}) 대신 이 값으로 만든다. 그 초는 열차 상태가 바뀔 때만
 * 정해지는 정적 값이고 전역 출발 뒤에는 실측보다 평균 54초 길다(2026-10-07, 전 노선 3,820구간). 같은 구간을 먼저 지난
 * 열차들의 실측 중앙값은 치우침 0, 오차 15초였다.
 *
 * <p>구간은 <b>도착하는 역</b>으로 부른다 — 도착 API 가 그 역 행에 "전역 출발(3)"을 싣기 때문이다. 시각은 전부 같은
 * 원천의 {@code recptnDt} 라 원천 지연이 양쪽에 똑같이 들어 있다.
 *
 * <p>사라져도 되는 값이다. 없으면 링은 기존대로 멈춘다(STALE).
 */
public interface SubwaySegmentTimePort {

    /** 그 역 행이 "전역 출발"로 처음 보였다. 이미 있으면 두지만, 15분 넘은 옛 값(같은 번호의 이전 운행)은 덮는다. */
    void recordDeparted(String lineId, String direction, String stationId, String trainNo, Instant at);

    /** 그 역 도착(또는 진입)이 보였다. 출발 기록이 있으면 소요시간 표본을 더하고 출발 기록을 지운다. */
    void recordArrived(String lineId, String direction, String stationId, String trainNo, Instant at);

    /** 그 열차가 이 역을 향해 전역을 출발한 것으로 처음 보인 시각. */
    Optional<Instant> departedAt(String lineId, String direction, String stationId, String trainNo);

    /** 이 구간을 최근에 지난 열차들의 소요시간(초), 최신순 최대 3개. */
    List<Long> recentSeconds(String lineId, String direction, String stationId);
}
