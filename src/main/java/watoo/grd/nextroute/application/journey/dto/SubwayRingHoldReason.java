package watoo.grd.nextroute.application.journey.dto;

/**
 * 링이 왜 느리거나 멈춰 있는지 — 앱 문구("○○ 정차 중"·늦어짐)용.
 *
 * <p>왜 그런지를 말해 앱이 "고장"이 아니라 "대기"로 보여 준다(컴팩트 Dynamic Island 에서는 링만 보인다). 관측이 낡아 멈춘
 * 경우는 여기 없다 — 그것은 {@link SubwayRingQuality#STALE} 이 말한다.
 */
public enum SubwayRingHoldReason {

    /**
     * 열차가 다음 역의 바로 앞 역에 진입했거나 서 있다. 전형 정차 시간을 알면 링은 정차 지점까지 <b>느리게 진행</b>하고, 정차가
     * 길어지면 거기서 멈춘다. 모르면 그 자리에 선다.
     */
    PREV_STATION_STOPPED,

    /** 실측·전형 도착 예정이 유예까지 지났는데 진입·도착이 안 보인다. 상한에서 기다린다. */
    LATE
}
