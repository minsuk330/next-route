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
 * @param realtimeSupported false 면 그 노선은 지하철 실시간이 없다(지방 지하철). 열차 판정을 열지 않았고 지하철
 *                          안내({@code subwayGuidance})도 오지 않는다 — 앱은 시간표·형상 기반 화면을 그리고 관측을 더
 *                          보내지 않아도 된다. 그때 {@code status} 는 의미 없는 {@code PENDING} 이다
 */
public record SubwayBindingView(
        SubwayBindingStatus status,
        int version,
        int legIndex,
        boolean realtimeSupported
) {

    public SubwayBindingView(SubwayBindingStatus status, int version, int legIndex) {
        this(status, version, legIndex, true);
    }

    public static SubwayBindingView from(SubwayLegBinding binding) {
        return new SubwayBindingView(binding.getStatus(), binding.getVersion(),
                binding.getLegIndex());
    }

    /** 실시간이 없는 지하철 단계. 판정 행을 만들지 않는다. */
    public static SubwayBindingView unsupported(int legIndex) {
        return new SubwayBindingView(SubwayBindingStatus.PENDING, 0, legIndex, false);
    }
}
