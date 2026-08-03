package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;

import java.time.Instant;
import java.util.List;

/**
 * 앱이 여정 화면을 그리는 데 필요한 최소 상태.
 *
 * <p>경로 원본(JSON) 은 넣지 않는다. 앱은 이미 검색 결과를 들고 있고, 서버가 매 응답마다
 * 같은 JSON 을 다시 실어 보내면 지하철 안에서 가장 무거운 응답이 된다.
 *
 * <p>{@code serverTime} 을 함께 준다. 기기 시계는 어긋나 있을 수 있어서, 앱이
 * {@code guidanceLeaseUntil} 까지 남은 시간을 계산하려면 서버 기준 지금이 필요하다.
 *
 * @param currentLeg             지금 지나는 단계. {@code legs} 에서 뽑은 값이라 중복이지만, 앱이
 *                               매번 순번으로 찾지 않아도 되게 함께 준다
 * @param recommendedPollSeconds 다음에 물어볼 때까지 기다릴 초. 0 이면 그만 물어도 된다
 */
public record JourneySnapshot(
        Long journeyId,
        JourneyStatus status,
        long commandVersion,
        long evaluationVersion,
        int routeRevision,
        int currentLegIndex,
        Instant guidanceLeaseUntil,
        Instant subwayOfflineProtectionUntil,
        Instant serverTime,
        JourneyLegView currentLeg,
        int recommendedPollSeconds,
        List<JourneyLegView> legs
) {

    /**
     * 폴링 간격. 단계 종류마다 상황이 바뀌는 속도가 달라서 하나로 정하지 않는다.
     *
     * <p>가장 짧은 것이 기다리는 단계다. "언제 오나"가 초 단위로 바뀌고 사용자가 화면을 가장
     * 자주 보는 구간이기 때문이다. 걷는 동안은 서버가 알려 줄 새 소식이 거의 없다.
     *
     * <p>Phase 3 에서 실시간 평가가 붙으면 남은 시간에 따라 더 좁혀야 한다. 지금은 단계 종류만
     * 보는 단순한 규칙이다.
     */
    private static final int POLL_WAITING = 10;
    private static final int POLL_RIDING = 15;
    private static final int POLL_WALKING = 30;
    private static final int POLL_STOPPED = 0;

    public JourneySnapshot {
        legs = legs == null ? List.of() : List.copyOf(legs);
    }

    /** 저장된 상태에서 스냅샷을 만든다. 파생 값(현재 단계·폴링 간격)은 여기서 계산한다. */
    public static JourneySnapshot of(Long journeyId, JourneyStatus status,
                                     long commandVersion, long evaluationVersion,
                                     int routeRevision, int currentLegIndex,
                                     Instant guidanceLeaseUntil,
                                     Instant subwayOfflineProtectionUntil,
                                     Instant serverTime,
                                     List<JourneyLegView> legs) {
        JourneyLegView current = legOf(legs, currentLegIndex);
        return new JourneySnapshot(journeyId, status, commandVersion, evaluationVersion,
                routeRevision, currentLegIndex, guidanceLeaseUntil,
                subwayOfflineProtectionUntil, serverTime,
                current, pollSecondsOf(status, current), legs);
    }

    private static JourneyLegView legOf(List<JourneyLegView> legs, int index) {
        if (legs == null || index < 0 || index >= legs.size()) {
            return null;
        }
        return legs.get(index);
    }

    private static int pollSecondsOf(JourneyStatus status, JourneyLegView currentLeg) {
        if (status != JourneyStatus.ACTIVE || currentLeg == null) {
            return POLL_STOPPED;
        }
        JourneyLegType type = currentLeg.type();
        if (type.isWaiting()) {
            return POLL_WAITING;
        }
        if (type.isRiding()) {
            return POLL_RIDING;
        }
        return POLL_WALKING;
    }
}
