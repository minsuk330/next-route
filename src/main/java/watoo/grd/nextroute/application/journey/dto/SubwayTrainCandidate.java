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
 * @param observedAt     원천 수신 시각. 떠난 열차는 출발 시각
 * @param kind           곧 올 열차인지, 이 역을 이미 떠난 열차인지. 떠난 열차는 {@code arrivalSeconds} 가 null 이고
 *                       {@code destination}·{@code trainType} 도 원천에 없으면 null 이다(위치 원천은 행선지를 주지 않는다)
 */
public record SubwayTrainCandidate(String trainNo, String destination, boolean express, String trainType,
                                   Integer arrivalSeconds, Instant observedAt, Kind kind) {

    /** 앱이 "이 열차 탈게요"/"이미 탔어요"를 가르는 값. */
    public enum Kind { UPCOMING, DEPARTED }
}
