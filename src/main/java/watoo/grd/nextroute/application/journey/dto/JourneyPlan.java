package watoo.grd.nextroute.application.journey.dto;

import java.util.List;

/**
 * 경로 하나를 여정 단계로 쪼갠 결과. 저장소와 무관한 순수 값이다.
 *
 * <p>이 값을 엔티티로 옮기는 일은 여정 시작 쪽이 맡는다. 계산기가 엔티티를 직접 만들면 "쪼개기가
 * 맞는가"를 확인하려 할 때마다 DB 가 필요해진다.
 */
public record JourneyPlan(
        List<JourneyLegDraft> legs,
        List<TransferConnectionDraft> transfers
) {

    public JourneyPlan {
        legs = legs == null ? List.of() : List.copyOf(legs);
        transfers = transfers == null ? List.of() : List.copyOf(transfers);
    }

    /** 쪼갤 것이 없었는지. 시작을 거절할지는 이 값을 받는 쪽이 정한다. */
    public boolean isEmpty() {
        return legs.isEmpty();
    }
}
