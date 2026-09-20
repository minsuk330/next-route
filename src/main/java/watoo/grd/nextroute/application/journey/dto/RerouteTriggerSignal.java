package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.journey.entity.TransferStatus;

import java.time.Instant;

/**
 * 재탐색을 찾아볼 만한 사건 하나.
 *
 * <p>이 PR 은 이 신호를 만들고 세기만 한다(shadow). 실제로 후보를 찾는 것은 ④이고, 그때
 * 이 신호가 {@code RerouteProposalService.request(...)} 로 넘어간다.
 *
 * @param previousStatus 직전 판정의 상태. 첫 평가면 null
 * @param currentStatus  이번 판정의 상태. <b>{@code UNKNOWN} 일 수 없다</b> — 모르는 상태는
 *                       방아쇠가 아니다
 * @param userReadyAt    사용자가 그 환승에서 탈 준비를 마치는 시각. 문을 연 근거다
 */
public record RerouteTriggerSignal(
        Long journeyId,
        int routeRevision,
        int transferIndex,
        Reason reason,
        TransferStatus previousStatus,
        TransferStatus currentStatus,
        Instant userReadyAt) {

    /**
     * 왜 찾아볼 만한가.
     *
     * <p>지표 태그로 쓰인다. 값 종류가 둘로 고정이라 시계열이 늘지 않는다(REQUIREMENTS §6).
     */
    public enum Reason {

        /** 환승 상태가 나빠졌다. evaluator 가 이미 계산한 전이를 그대로 읽는다. */
        DEGRADED,

        /**
         * 타려던 차의 예정 시각이 지났는데 아직 못 탔다.
         *
         * <p>실시간 API 가 흔들리는 것과 다른 사건이다 — 시각이 지났다는 것은 API 상태와
         * 무관하게 참이다. 그래서 <b>긍정적 근거</b>로 인정한다.
         */
        TARGET_PASSED;

        /** 지표 태그 값. {@code degraded} · {@code target-passed}. */
        public String tag() {
            return name().toLowerCase().replace('_', '-');
        }
    }
}
