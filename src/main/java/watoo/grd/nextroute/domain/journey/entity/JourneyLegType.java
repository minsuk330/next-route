package watoo.grd.nextroute.domain.journey.entity;

/**
 * 여정 한 단계의 종류. 값 목록은 V35 의 {@code journey_leg_type_check} 가 소유한다.
 *
 * <p>버스·지하철 한 구간은 <b>기다리기와 타고 가기 두 단계</b>로 쪼갠다. "탔어요 / 내렸어요"를
 * 누르는 지점이 단계 경계에 있어야 상태 전이가 단순해지기 때문이다.
 */
public enum JourneyLegType {

    /** 출발지에서 첫 정류장까지, 또는 마지막 정류장에서 목적지까지 걷기. */
    WALK,

    /** 정류장에서 버스를 기다리는 중. */
    WAIT_BUS,

    /** 버스에 타고 이동 중. */
    RIDE_BUS,

    /** 승강장에서 열차를 기다리는 중. */
    WAIT_SUBWAY,

    /** 열차에 타고 이동 중. */
    RIDE_SUBWAY,

    /** 교통수단과 교통수단 사이의 걷기(환승 도보). */
    TRANSFER_WALK;

    /** 기다리는 단계인지. 노선 후보({@code journey_leg_route_option})는 이 단계가 소유한다. */
    public boolean isWaiting() {
        return this == WAIT_BUS || this == WAIT_SUBWAY;
    }

    /** 타고 가는 단계인지. */
    public boolean isRiding() {
        return this == RIDE_BUS || this == RIDE_SUBWAY;
    }
}
