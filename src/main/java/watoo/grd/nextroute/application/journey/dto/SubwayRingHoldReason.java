package watoo.grd.nextroute.application.journey.dto;

/**
 * 링이 <b>정상적으로</b> 멈춰 있는 이유.
 *
 * <p>멈춘 링은 진행률을 지어내지 않는다. 대신 왜 멈췄는지를 말해 앱이 "고장"이 아니라 "대기"로 보여 준다
 * (컴팩트 Dynamic Island 에서는 빈 링만 보인다). 관측이 낡아 멈춘 경우는 여기 없다 — 그것은
 * {@link SubwayRingQuality#STALE} 이 말한다.
 */
public enum SubwayRingHoldReason {

    /** 열차가 다음 역의 바로 앞 역에 진입했거나 서 있다. 아직 출발 전이라 진행이 없다. */
    PREV_STATION_STOPPED,

    /** 앞 열차 실측으로 잡은 도착 예정이 지났는데 도착이 안 보인다. 상한에서 기다린다. */
    LATE
}
