package watoo.grd.nextroute.application.journey.dto;

import java.time.Instant;

/**
 * 지금 진행 중인 단계가 언제 끝나는지.
 *
 * <p>{@code arrivalAt} 은 <b>절대 시각</b>이다. "몇 분 남음"이 아니라 "10시 39분 30초 도착"으로
 * 내려보내는 이유는 앱이 그것을 기준으로 초 단위 countdown 을 스스로 돌리기 때문이다. 남은
 * 시간을 내려보내면 응답이 오는 동안 흐른 시간만큼 어긋난다.
 *
 * @param arrivalAt                단계 종료 예상 시각
 * @param remainingSeconds         그 시각까지 남은 초. 관측 시점 기준이다
 * @param remainingDistanceMeters  남은 거리(m). 알 수 없으면 null
 * @param remainingStops           남은 정류장 수. 버스가 아니거나 모르면 null
 * @param source                   무엇으로 계산했는지
 * @param quality                  얼마나 믿을 수 있는지
 * @param observedAt               계산의 기준이 된 관측 시각
 */
public record CurrentLegArrival(
        Instant arrivalAt,
        int remainingSeconds,
        Integer remainingDistanceMeters,
        Integer remainingStops,
        Source source,
        Quality quality,
        Instant observedAt
) {

    /** 무엇을 근거로 계산했는가. */
    public enum Source {
        /** 앱 GPS 를 정적 geometry 에 투영한 진행도. 이동 단계의 기본이다. */
        CLIENT_GEOMETRY_PROGRESS,
        /**
         * 실시간 도착 정보. 기다리는 단계의 기본이다.
         *
         * <p>여기서는 사용자가 어디까지 왔는지가 아니라 <b>차량이 어디까지 왔는지</b>가 답이다.
         */
        REALTIME_ARRIVAL,
        /** 진행도가 없어 계획 소요시간만 썼다. */
        PLANNED_DURATION
    }

    /**
     * 신뢰도.
     *
     * <p>{@code UNKNOWN} 을 따로 두는 것이 중요하다. 모르는 것을 그럴듯한 숫자로 채우면 사용자는
     * 그 숫자를 믿고 행동한다. 1차에서는 잘못된 확신보다 명시적인 "모름"을 택한다.
     */
    public enum Quality {
        /** 최근 관측으로 계산했다. */
        GOOD,
        /** 관측이 오래됐거나 좌표가 실측이 아니다. 값은 주지만 오차가 크다. */
        APPROXIMATE,
        /** 계산할 근거가 없다. */
        UNKNOWN
    }

    public static CurrentLegArrival unknown() {
        return new CurrentLegArrival(null, 0, null, null, null, Quality.UNKNOWN, null);
    }

    public boolean isUnknown() {
        return quality == Quality.UNKNOWN;
    }
}
