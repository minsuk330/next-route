package watoo.grd.nextroute.application.journey.dto;

/**
 * 평가기가 묶인 버스 대기 중 서버 기본 선택을 먼저 오는 노선으로 옮긴 결정(#614 PR-2).
 *
 * <p>계산(트랜잭션 밖)에서 정하고, 저장은 평가기 소유권 확인 뒤 같은 트랜잭션에서 "선택 그대로·DEFAULT·대기 중" 을 다시 보고 한다.
 *
 * @param waitLegIndex   옮길 대기 단계
 * @param rideLegIndex   짝 탑승 단계(없으면 null)
 * @param fromOptionId   계산 때 선택 — 저장 직전 이 값이 그대로여야 적용한다
 * @param toOptionId     새 선택
 * @param toRouteId      새 선택의 노선 id(단계 routeId)
 * @param toRouteName    로그용
 * @param toRideSeconds  새 노선 탑승시간(초). 모르면 null — 탑승 단계 계획 소요는 그대로
 * @param fromWaitSeconds 정류장 도착 정보상 옛 노선 다음 차까지(초). 차 없음이면 null
 * @param toWaitSeconds  새 노선 다음 차까지(초)
 */
public record LaneReselection(
        int waitLegIndex,
        Integer rideLegIndex,
        Long fromOptionId,
        Long toOptionId,
        String toRouteId,
        String toRouteName,
        Integer toRideSeconds,
        Integer fromWaitSeconds,
        int toWaitSeconds
) {
}
