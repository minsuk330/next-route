package watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 사용자가 손으로 고칠 대상.
 *
 * <p>여정 번호만으로는 부족하다. 환승이 있으면 한 여정에 지하철 단계가 둘 이상이고, 그때
 * 어느 쪽을 되돌릴지 정해지지 않는다.
 */
public record SubwayBindingCorrectionRequest(
        @NotNull Integer routeRevision,
        @NotNull @Min(0) Integer legIndex
) {
}
