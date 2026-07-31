package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.util.List;

/**
 * 여정 한 단계의 계획 값. 아직 저장되지 않았고 id 도 없다.
 *
 * <p>노선 후보는 <b>기다리는 단계</b>가 들고 있다. 짝이 되는 타는 단계는 {@link #pairedLegIndex}
 * 로 상대를 가리키기만 한다 — 후보를 두 벌 만들면 둘이 어긋날 수 있기 때문이다.
 *
 * @param legIndex           경로 안에서의 순번. 0 부터
 * @param type               걷기 / 기다리기 / 타고 가기
 * @param state              시작 시점의 진행 상태. 0번만 {@code ACTIVE}
 * @param startStopId        출발 지점 식별자. 검색 응답에 없으면 null
 * @param endStopId          도착 지점 식별자. 검색 응답에 없으면 null
 * @param startName          출발 지점 이름
 * @param endName            도착 지점 이름
 * @param plannedDurationSec 검색 결과가 말한 소요시간(초). 기다리는 단계는 알 수 없어 null
 * @param evidenceQuality    실시간 대조 가능성. 식별자가 없으면 {@code "UNKNOWN"}, 아니면 null
 * @param routeOptions       노선 후보들. 기다리는 단계만 비어 있지 않다
 * @param pairedLegIndex     짝이 되는 단계의 순번(기다리기↔타고가기). 걷기는 null
 */
public record JourneyLegDraft(
        int legIndex,
        JourneyLegType type,
        JourneyLegState state,
        String startStopId,
        String endStopId,
        String startName,
        String endName,
        Integer plannedDurationSec,
        String evidenceQuality,
        List<JourneyRouteOptionDraft> routeOptions,
        Integer pairedLegIndex
) {

    public JourneyLegDraft {
        routeOptions = routeOptions == null ? List.of() : List.copyOf(routeOptions);
    }

    /** 후보가 하나뿐인지. 이때만 시작 시점에 탈 노선을 확정할 수 있다. */
    public boolean hasSingleRouteOption() {
        return routeOptions.size() == 1;
    }
}
