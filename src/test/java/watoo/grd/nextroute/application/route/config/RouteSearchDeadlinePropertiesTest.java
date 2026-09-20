package watoo.grd.nextroute.application.route.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검색 예산 기본값 계약.
 *
 * <p>전체 예산은 OTP 호출과 도착예측 보강을 <b>둘 다</b> 담아야 한다. 이 값이 도로 줄면
 * 보강이 상시로 잘려 {@code transferArrivals} 가 빈 채 나가고, 그 증상은 "모델이 답을 안 준다"로
 * 보인다 — 실제로는 부르지도 못한 것이다.
 */
class RouteSearchDeadlinePropertiesTest {

    @Test
    @DisplayName("전체 예산은 OTP(최대 otp-ms) + 보강(transfer-arrival deadline) 을 담을 만큼 넓다")
    void deadlineCoversOtpAndEnrichment() {
        RouteSearchDeadlineProperties props = new RouteSearchDeadlineProperties();
        TransferArrivalProperties transfer = new TransferArrivalProperties();

        assertThat(props.getDeadlineMs()).isEqualTo(4500);
        // OTP 실측 상한(2.0s 대) + 보강 상한(1.5s) 이 들어가고도 남아야 한다.
        assertThat(props.getDeadlineMs()).isGreaterThan(2000 + transfer.getDeadlineMs());
    }
}
