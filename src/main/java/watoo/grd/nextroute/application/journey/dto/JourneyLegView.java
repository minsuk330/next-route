package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.util.List;

/**
 * 여정 한 단계의 조회용 모습.
 *
 * <p>ODsay 첫 lane을 현재 선택 노선으로 사용하고 나머지는 대체 후보로 보존한다. 이 선택은
 * 실제 차량·운행 회차를 식별한다는 뜻이 아니다.
 *
 * <p>안내 시트 지점 줄 값(#266)은 전부 nullable — 없으면 앱이 줄을 숨긴다.
 * {@code startStopNo}/{@code endStopNo} 는 버스 정류장 번호(대기 단계는 둘 다 승차 정류장),
 * {@code directionName} 은 지하철 승차역 다음 역 이름, {@code startExitNo}/{@code endExitNo} 는 지하철 출구,
 * {@code fastAlight} 는 지하철 빠른 하차 칸·문, {@code alightDoorSide} 는 지하철 하차역 내리는 문 {@code LEFT}·{@code RIGHT}(#335).
 * {@code trainKind} 는 지하철 단계(기다리기·타고 가기)의 열차 종류 {@code NORMAL}·{@code EXPRESS}·{@code DIRECT}·{@code ITX}
 * (subway-express S2) — 앱 단계({@code JourneyLeg}) 배지의 근거다. 버스·도보·배포 전 여정은 null.
 * {@code sourceStartStopId}/{@code sourceEndStopId} 는 검색 원천(OTP)의 정류장 id({@code 1:RS_ACC1_S-2-0219}) —
 * 검색 응답 {@code stations[].stationID} 와 같은 값이다(#593). {@code startStopId}/{@code endStopId} 는 실시간 체계 id(서울 역·정류장)라
 * 지방 지하철은 비어 있고, 앱은 지방 시간표({@code /api/subway/regional/departures})를 이 값으로 부른다. 대기 단계는 둘 다 승차 정류장.
 */
public record JourneyLegView(
        Long legId,
        int legIndex,
        JourneyLegType type,
        JourneyLegState state,
        String startStopId,
        String endStopId,
        String startName,
        String endName,
        String routeId,
        Integer plannedDurationSec,
        String evidenceQuality,
        Long selectedRouteOptionId,
        List<JourneyRouteOptionView> routeOptions,
        String startStopNo,
        String endStopNo,
        String directionName,
        String startExitNo,
        String endExitNo,
        List<FastAlight> fastAlight,
        String alightDoorSide,
        String trainKind,
        String sourceStartStopId,
        String sourceEndStopId
) {

    public JourneyLegView {
        routeOptions = routeOptions == null ? List.of() : List.copyOf(routeOptions);
        fastAlight = fastAlight == null ? null : List.copyOf(fastAlight);
    }

    /** 원천 정류장 id 없는 단계(#593 이전 계약). */
    public JourneyLegView(Long legId, int legIndex, JourneyLegType type, JourneyLegState state,
                          String startStopId, String endStopId, String startName, String endName,
                          String routeId, Integer plannedDurationSec, String evidenceQuality,
                          Long selectedRouteOptionId, List<JourneyRouteOptionView> routeOptions,
                          String startStopNo, String endStopNo, String directionName,
                          String startExitNo, String endExitNo, List<FastAlight> fastAlight, String alightDoorSide,
                          String trainKind) {
        this(legId, legIndex, type, state, startStopId, endStopId, startName, endName, routeId,
                plannedDurationSec, evidenceQuality, selectedRouteOptionId, routeOptions,
                startStopNo, endStopNo, directionName, startExitNo, endExitNo, fastAlight, alightDoorSide, trainKind,
                null, null);
    }

    /** 안내 값 없는 단계(#266 이전 계약). */
    public JourneyLegView(Long legId, int legIndex, JourneyLegType type, JourneyLegState state,
                          String startStopId, String endStopId, String startName, String endName,
                          String routeId, Integer plannedDurationSec, String evidenceQuality,
                          Long selectedRouteOptionId, List<JourneyRouteOptionView> routeOptions) {
        this(legId, legIndex, type, state, startStopId, endStopId, startName, endName, routeId,
                plannedDurationSec, evidenceQuality, selectedRouteOptionId, routeOptions,
                null, null, null, null, null, null, null, null);
    }

    /** 내리는 문 방향 없는 단계(#335 이전 계약). */
    public JourneyLegView(Long legId, int legIndex, JourneyLegType type, JourneyLegState state,
                          String startStopId, String endStopId, String startName, String endName,
                          String routeId, Integer plannedDurationSec, String evidenceQuality,
                          Long selectedRouteOptionId, List<JourneyRouteOptionView> routeOptions,
                          String startStopNo, String endStopNo, String directionName,
                          String startExitNo, String endExitNo, List<FastAlight> fastAlight) {
        this(legId, legIndex, type, state, startStopId, endStopId, startName, endName, routeId,
                plannedDurationSec, evidenceQuality, selectedRouteOptionId, routeOptions,
                startStopNo, endStopNo, directionName, startExitNo, endExitNo, fastAlight, null, null);
    }

    /** 열차 종류 없는 단계(subway-express S2 이전 계약). */
    public JourneyLegView(Long legId, int legIndex, JourneyLegType type, JourneyLegState state,
                          String startStopId, String endStopId, String startName, String endName,
                          String routeId, Integer plannedDurationSec, String evidenceQuality,
                          Long selectedRouteOptionId, List<JourneyRouteOptionView> routeOptions,
                          String startStopNo, String endStopNo, String directionName,
                          String startExitNo, String endExitNo, List<FastAlight> fastAlight, String alightDoorSide) {
        this(legId, legIndex, type, state, startStopId, endStopId, startName, endName, routeId,
                plannedDurationSec, evidenceQuality, selectedRouteOptionId, routeOptions,
                startStopNo, endStopNo, directionName, startExitNo, endExitNo, fastAlight, alightDoorSide, null);
    }

    /**
     * 빠른 하차 칸·문 하나(#266).
     *
     * @param carDoor  {@code "2-3"} = 2번 칸 3번 문
     * @param facility 그 문 앞 승강장의 이동 시설: {@code STAIRS} | {@code ESCALATOR} | {@code ELEVATOR}
     */
    public record FastAlight(String carDoor, String facility) {
    }
}
