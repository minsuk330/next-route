package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

/**
 * "이 정류장에서 이 노선을 타려는데 차가 언제 오나"를 물을 때 필요한 값 전부.
 *
 * <p>엔티티가 아니라 값만 담는 이유는 <b>아직 저장되지 않은 경로</b>도 물어봐야 하기 때문이다.
 * 재탐색이 방금 검색해 온 후보 경로는 DB 에 없다 — 그래서 {@code JourneyLeg} 를 받는 기존
 * 예측기를 그대로 쓸 수 없다.
 *
 * @param waitType    {@code WAIT_BUS} / {@code WAIT_SUBWAY}
 * @param stopId      내부 정류장·역 ID. 미해석이면 null
 * @param stopName    역 이름. 지하철 실시간 API 가 이름으로만 조회를 받는다
 * @param routeId     내부 노선 ID
 * @param boardingSeq 버스 승차 정류장 순번. 지하철은 null
 * @param wayCode     지하철 방향. 버스는 null
 * @param sourceRouteId 지역(경기·TAGO) 노선 실시간 id. 서울 {@code routeId} 로 안 풀린 버스 구간을 지역 실시간으로 물을 때만 쓴다
 *                      (regional-bus-parity PR-1). null 이면 지역 조회를 하지 않는다 — 수락 트랜잭션처럼 기다리면 안 되는 곳은 넘기지 않는다
 */
public record BoardingQuery(JourneyLegType waitType, String stopId, String stopName,
                            String routeId, Integer boardingSeq, Integer wayCode, String sourceRouteId) {

    /** 지역 조회 없는 질의(기존 호출부). */
    public BoardingQuery(JourneyLegType waitType, String stopId, String stopName,
                         String routeId, Integer boardingSeq, Integer wayCode) {
        this(waitType, stopId, stopName, routeId, boardingSeq, wayCode, null);
    }
}
