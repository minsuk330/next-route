package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus;
import watoo.grd.nextroute.domain.journey.entity.SubwayLegBinding;

/**
 * 앱에게 돌려주는 고정 상태.
 *
 * <p><b>열차번호를 담지 않는다.</b> 앱이 그 값으로 할 수 있는 일이 없고, 내보내는 순간 앱이
 * 그것을 근거로 무언가를 판단하기 시작한다. 서버 내부 식별자로만 둔다.
 *
 * <p>{@code legIndex} 를 함께 돌려주는 이유. 앱이 대기 단계 번호로 보내도 서버는 짝이 되는
 * 타는 단계에 적는다. 어디에 적혔는지 알려 주지 않으면 앱은 다음 요청을 또 대기 단계로 보낸다.
 *
 * @param status  아직 열차를 못 골랐으면 {@code PENDING}, 골랐지만 확정 전이면 {@code PROVISIONAL}
 * @param version 낙관적 잠금 값. 앱이 쓰지는 않지만 장애 때 서버 로그와 맞춰 보는 데 필요하다
 */
public record SubwayBindingView(
        SubwayBindingStatus status,
        int version,
        int legIndex
) {

    public static SubwayBindingView from(SubwayLegBinding binding) {
        return new SubwayBindingView(binding.getStatus(), binding.getVersion(),
                binding.getLegIndex());
    }
}
