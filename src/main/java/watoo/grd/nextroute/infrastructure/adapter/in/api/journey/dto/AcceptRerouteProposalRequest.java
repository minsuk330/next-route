package watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 수락 요청.
 *
 * <p>{@code candidateId} 는 <b>필수</b>다. 빠지면 서버가 1번 후보를 적용하는 대신 거절한다 —
 * 사용자가 고르지 않은 경로로 여정이 바뀌는 것이 이 기능에서 가장 나쁜 사고다.
 */
public record AcceptRerouteProposalRequest(
        @NotBlank String candidateId
) {
}
