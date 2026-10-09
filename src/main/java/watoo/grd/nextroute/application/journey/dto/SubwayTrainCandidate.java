package watoo.grd.nextroute.application.journey.dto;

import java.time.Instant;

/**
 * 역에서 고를 수 있는 열차 하나(#265). 행선지·급행은 사용자가 급행·다른 지선을 거르는 근거다.
 *
 * <p>{@link SubwayBindingView} 와 달리 <b>열차번호를 싣는다</b> — 선택 요청의 키다. 앱이 이 값으로 다른 판단을 하지
 * 않게 HANDOFF 에 적는다.
 *
 * @param arrivalSeconds 지금 기준 남은 초. 원천이 미산출이면 null
 * @param trainType      원천 열차 종류 원문(일반·급행 등). 없으면 null
 * @param observedAt     원천 수신 시각
 */
public record SubwayTrainCandidate(String trainNo, String destination, boolean express, String trainType,
                                   Integer arrivalSeconds, Instant observedAt) {
}
