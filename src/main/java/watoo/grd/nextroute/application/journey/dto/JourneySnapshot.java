package watoo.grd.nextroute.application.journey.dto;

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
 */
public record JourneySnapshot(
        Long journeyId,
        JourneyStatus status,
        long commandVersion,
        long evaluationVersion,
        int routeRevision,
        int currentLegIndex,
        Instant guidanceLeaseUntil,
        Instant serverTime,
        List<JourneyLegView> legs
) {

    public JourneySnapshot {
        legs = legs == null ? List.of() : List.copyOf(legs);
    }
}
