package watoo.grd.nextroute.application.journey.dto;

/**
 * 한 구간(직전 역 → 다음 역) 안에서 열차가 어디쯤인지. 선언 순서가 진행 순서다 — 같은 구간에서 더 앞 단계 관측이 늦게 와도
 * 되돌아가지 않는다.
 *
 * <p><b>배포 뒤 값을 더하지 않는다</b>({@link SubwayRingEtaSource} 와 같은 이유). 내부 상태 전용이고 앱으로 나가지 않는다.
 */
public enum SubwayRingStage {
    /** 열차가 직전 역에 서 있다(전역 진입·도착). */
    DWELL,
    /** 직전 역을 떠나 달리는 중. */
    RUN,
    /** 예정이 지났는데 진입·도착이 안 보인다. */
    LATE,
    /** 다음 역 진입. */
    APPROACH,
    /** 다음 역 도착. */
    ARRIVED
}
