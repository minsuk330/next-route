package watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto;

import jakarta.validation.constraints.PositiveOrZero;

/**
 * "다시 찾아줘" 요청 본문.
 *
 * <p>사용자 ID 는 받지 않는다. 토큰에서만 읽는다 — 본문 값을 믿으면 남의 여정에 제안을 심을 수
 * 있다({@code JourneyTelemetryController} 와 같은 이유).
 *
 * @param transferIndex 어느 환승에 대한 재탐색인지
 */
public record CreateRerouteProposalRequest(@PositiveOrZero Integer transferIndex) {

    /** 값이 없으면 첫 환승으로 본다. 앱의 옛 버전이 필드를 안 보내도 요청이 죽지 않게. */
    public int transferIndexOrDefault() {
        return transferIndex == null ? 0 : transferIndex;
    }
}
