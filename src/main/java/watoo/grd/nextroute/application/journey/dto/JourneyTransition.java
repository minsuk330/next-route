package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.journey.entity.JourneyEndReason;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;

import java.util.List;

/**
 * 명령 하나를 받았을 때 <b>무엇이 어떻게 바뀌어야 하는가</b>에 대한 결정.
 *
 * <p>결정과 적용을 나눈 이유는 두 가지다. 첫째, 전이 규칙은 DB 없이 확인할 수 있어야 한다.
 * 둘째, 하나의 명령이 여러 표를 건드리므로(단계·여정·후보 선택) 적용은 한 트랜잭션이 모아서
 * 해야 한다. 이 값은 그 트랜잭션에 넘기는 지시서다.
 *
 * @param result                  적용·충돌·거절 중 하나. 재전송(DUPLICATE)은 저장된 이력을 봐야 알 수 있어 여기서 내지 않는다
 * @param rejectionReason         거절·충돌일 때의 이유. 적용이면 null
 * @param legStateChanges         바꿀 단계들
 * @param newCurrentLegIndex      바뀐 현재 단계. 그대로면 null
 * @param newStatus               바뀐 여정 상태. 그대로면 null
 * @param endReason               여정이 끝나는 경우의 이유
 * @param selectedRouteOptionId   확정할 노선 후보
 * @param routeOptionTargetLegs   그 후보를 가리킬 단계들(기다리기와 타고가기 짝)
 * @param commandVersionIncrement 명령 번호를 올릴지. 상태를 바꾸지 않는 명령은 올리지 않는다
 */
public record JourneyTransition(
        JourneyEventResult result,
        String rejectionReason,
        List<LegStateChange> legStateChanges,
        Integer newCurrentLegIndex,
        JourneyStatus newStatus,
        JourneyEndReason endReason,
        Long selectedRouteOptionId,
        List<Integer> routeOptionTargetLegs,
        boolean commandVersionIncrement
) {

    public JourneyTransition {
        legStateChanges = legStateChanges == null ? List.of() : List.copyOf(legStateChanges);
        routeOptionTargetLegs = routeOptionTargetLegs == null
                ? List.of() : List.copyOf(routeOptionTargetLegs);
    }

    public boolean isApplied() {
        return result == JourneyEventResult.APPLIED;
    }

    /**
     * 상태를 실제로 바꾸는 명령인지.
     *
     * <p>앱이 앞으로 나왔다는 신호처럼 아무것도 바꾸지 않는 명령도 <b>적용</b>이다. 거절이 아니다.
     * 다만 명령 번호를 올리지 않는다 — 올리면 사용자가 앱을 켰다 껐다 하는 것만으로 손에 든
     * 번호가 낡아져 정작 "탔어요"가 거절된다.
     */
    public static JourneyTransition noChange() {
        return new JourneyTransition(JourneyEventResult.APPLIED, null,
                List.of(), null, null, null, null, List.of(), false);
    }

    public static JourneyTransition rejected(String reason) {
        return new JourneyTransition(JourneyEventResult.REJECTED, reason,
                List.of(), null, null, null, null, List.of(), false);
    }

    /** 앱이 낡은 상태를 보고 눌렀다. 거절이 아니라 "다시 보고 다시 눌러라"에 가깝다. */
    public static JourneyTransition conflict(String reason) {
        return new JourneyTransition(JourneyEventResult.CONFLICT, reason,
                List.of(), null, null, null, null, List.of(), false);
    }
}
