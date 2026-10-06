package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.application.route.dto.PathResult;

import java.time.Instant;

/**
 * 후보 하나의 경로(#259). 앱이 말풍선을 누르면 받는다.
 *
 * <h2>{@code path} 는 수락이 적용하는 값과 같다</h2>
 * 저장된 {@link RerouteCandidateApply.RemainingPathApply#path()} 를 <b>가공 없이</b> 싣는다. 다시
 * 검색하면 사용자가 본 경로와 다른 경로가 적용된다. 타입이 검색 응답의 {@code paths[]} 원소와
 * 같은 {@link PathResult} 라 앱은 검색 결과 상세 화면을 그대로 태운다.
 *
 * <h2>지금 타는 구간은 {@code ride} 로 따로 준다</h2>
 * {@code path} 는 하차 정류장에서 시작한다. 그 앞(지금 타고 있는 구간)을 {@code path} 맨 앞에
 * 합성해 붙이면 {@code path} 가 수락 원천과 달라진다 — 그래서 칸을 나눈다(사용자 결정).
 *
 * @param ride 지금 타고 있는 구간. 타고 있지 않으면 null
 * @param path 이 후보를 고르면 적용되는 경로 원본
 */
public record RerouteCandidateRoute(
        Long proposalId,
        String candidateId,
        Ride ride,
        PathResult path) {

    /**
     * 지금 타고 있는 구간 — 상세 타임라인의 첫 줄.
     *
     * <p>칸마다 못 구하면 null 이다. 이름이나 정류장 수를 못 풀었다고 경로를 숨기지 않는다.
     *
     * @param legIndex       그 주행 단계의 순번(현재 개정본 기준)
     * @param routeName      노선 이름. 못 풀면 null
     * @param fromStopName   이 주행의 탑승 정류장 이름
     * @param alightStopId   내릴 정류장(서울 id)
     * @param alightStopName 내릴 정류장 이름
     * @param alightX        내릴 정류장 경도. 옛 저장 행이면 null
     * @param alightY        내릴 정류장 위도. 옛 저장 행이면 null
     * @param stopCount      지금 위치에서 내릴 정류장까지 남은 정류장 수. 위치를 모르면 null
     * @param alightAt       내리는 시각. 남은 시간은 앱이 지금 시각으로 계산한다 — 제안이 만들어진
     *                       뒤 시간이 흘렀으니 "몇 초 남았다"를 싣으면 이미 낡은 값이다
     */
    public record Ride(
            int legIndex,
            String routeName,
            String fromStopName,
            String alightStopId,
            String alightStopName,
            Double alightX,
            Double alightY,
            Integer stopCount,
            Instant alightAt) {
    }
}
