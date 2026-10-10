package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.journey.entity.JourneyLegGuidance;
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
 * @param startStopId        서버 내부 출발 정류장·역 ID. 아직 해석 전이면 null
 * @param endStopId          서버 내부 도착 정류장·역 ID. 아직 해석 전이면 null
 * @param sourceStartStopId  ODSAY가 준 출발 지점 ID. 내부 ID와 같은지 보장하지 않는다
 * @param sourceEndStopId    ODSAY가 준 도착 지점 ID. 내부 ID와 같은지 보장하지 않는다
 * @param startName          출발 지점 이름
 * @param endName            도착 지점 이름
 * @param plannedDurationSec 검색 결과가 말한 소요시간(초). 기다리는 단계는 알 수 없어 null
 * @param evidenceQuality    실시간 대조 가능성. 식별자가 없으면 {@code "UNKNOWN"}, 아니면 null
 * @param routeOptions       노선 후보들. 기다리는 단계만 비어 있지 않다
 * @param pairedLegIndex     짝이 되는 단계의 순번(기다리기↔타고가기). 걷기는 null
 * @param guidance           안내 시트 지점 줄 값(#266). 없으면 {@link JourneyLegGuidance#EMPTY}
 * @param alightPrevName     지하철 탑승 단계의 하차 직전 역 이름(#266 빠른 하차 방향 판정용, 저장하지 않는다). 그 밖은 null
 */
public record JourneyLegDraft(
        int legIndex,
        JourneyLegType type,
        JourneyLegState state,
        String startStopId,
        String endStopId,
        String sourceStartStopId,
        String sourceEndStopId,
        String startName,
        String endName,
        Double startX,
        Double startY,
        Double endX,
        Double endY,
        Integer plannedDurationSec,
        String evidenceQuality,
        List<JourneyRouteOptionDraft> routeOptions,
        Integer pairedLegIndex,
        JourneyLegGuidance guidance,
        String alightPrevName
) {

    public JourneyLegDraft {
        routeOptions = routeOptions == null ? List.of() : List.copyOf(routeOptions);
        guidance = JourneyLegGuidance.orEmpty(guidance);
    }

    /** 안내 값 없이 만든다. 값은 Compiler·{@code JourneyGuidanceInfoEnricher} 가 채운다. */
    public JourneyLegDraft(int legIndex, JourneyLegType type, JourneyLegState state,
                           String startStopId, String endStopId,
                           String sourceStartStopId, String sourceEndStopId,
                           String startName, String endName,
                           Double startX, Double startY, Double endX, Double endY,
                           Integer plannedDurationSec, String evidenceQuality,
                           List<JourneyRouteOptionDraft> routeOptions, Integer pairedLegIndex) {
        this(legIndex, type, state, startStopId, endStopId, sourceStartStopId, sourceEndStopId,
                startName, endName, startX, startY, endX, endY, plannedDurationSec, evidenceQuality,
                routeOptions, pairedLegIndex, JourneyLegGuidance.EMPTY, null);
    }

    public JourneyLegDraft(int legIndex, JourneyLegType type, JourneyLegState state,
                           String startStopId, String endStopId,
                           String sourceStartStopId, String sourceEndStopId,
                           String startName, String endName,
                           Double startX, Double startY, Double endX, Double endY,
                           Integer plannedDurationSec, String evidenceQuality,
                           List<JourneyRouteOptionDraft> routeOptions, Integer pairedLegIndex,
                           JourneyLegGuidance guidance) {
        this(legIndex, type, state, startStopId, endStopId, sourceStartStopId, sourceEndStopId,
                startName, endName, startX, startY, endX, endY, plannedDurationSec, evidenceQuality,
                routeOptions, pairedLegIndex, guidance, null);
    }

    public JourneyLegDraft withGuidance(JourneyLegGuidance guidance) {
        return new JourneyLegDraft(legIndex, type, state, startStopId, endStopId,
                sourceStartStopId, sourceEndStopId, startName, endName, startX, startY, endX, endY,
                plannedDurationSec, evidenceQuality, routeOptions, pairedLegIndex, guidance, alightPrevName);
    }

    /** 내부 ID 해석 결과를 반영한 새 draft. Compiler는 원천 ID만 채우고 이 단계가 내부 ID를 채운다. */
    public JourneyLegDraft withResolvedStops(String resolvedStartStopId, String resolvedEndStopId,
                                             String resolvedEvidenceQuality,
                                             List<JourneyRouteOptionDraft> resolvedOptions) {
        return new JourneyLegDraft(legIndex, type, state,
                resolvedStartStopId, resolvedEndStopId,
                sourceStartStopId, sourceEndStopId,
                startName, endName, startX, startY, endX, endY,
                plannedDurationSec, resolvedEvidenceQuality, resolvedOptions, pairedLegIndex, guidance,
                alightPrevName);
    }

    /** 컴파일·해석 테스트에서 후보 개수를 명시적으로 확인할 때 쓴다. */
    public boolean hasSingleRouteOption() {
        return routeOptions.size() == 1;
    }
}
