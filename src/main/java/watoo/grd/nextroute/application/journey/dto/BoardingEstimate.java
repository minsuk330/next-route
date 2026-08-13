package watoo.grd.nextroute.application.journey.dto;

import java.time.Instant;

/**
 * 승차 시각 추정 결과.
 *
 * @param boardingAt   그 차를 타게 될 시각
 * @param boardable    사용자가 준비를 마치는 시각 이후에 오는 차인가.
 *                     <b>추정기는 이 값으로 걸러내지 않는다</b> — 소비자마다 뜻이 정반대다.
 *                     여정 평가는 못 타는 차의 음수 여유로 "환승 놓침"을 판정하므로 그대로 통과시키고,
 *                     재탐색 후보는 지나간 차를 제안할 수 없으므로 버린다
 * @param source       실시간으로 잡았는지 모델이 추정했는지
 * @param quality      얼마나 믿을 수 있는지
 * @param observedAt   근거가 만들어진 시각. 화면 신선도 표시에 쓴다
 * @param modelVersion 모델이 추정한 경우의 모델 이름. 실시간이거나 모르면 null
 */
public record BoardingEstimate(Instant boardingAt, boolean boardable,
                               CurrentLegArrival.Source source, CurrentLegArrival.Quality quality,
                               Instant observedAt, String modelVersion) {
}
