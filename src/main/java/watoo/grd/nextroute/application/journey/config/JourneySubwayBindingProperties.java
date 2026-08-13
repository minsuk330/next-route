package watoo.grd.nextroute.application.journey.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 지하철 탑승 열차 판정 설정.
 *
 * <p>지금은 관측을 받아 두는 데 필요한 값만 있다. 판정 관문 값은 판정기가 붙을 때 함께 들어온다 —
 * 실기기 관측 없이 숫자를 먼저 정해 두면 그 숫자가 근거인 척한다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "journey.subway.binding")
public class JourneySubwayBindingProperties {

    /** 기본값 off. 판정기가 없으면 관측은 쌓이기만 한다. */
    private boolean enabled = false;

    /**
     * 기기 시각이 서버보다 이만큼 뒤처지면 의심한다.
     *
     * <p>의심한다고 버리지 않는다 — 버리면 시계가 틀어진 기기는 영영 안내를 못 받고, 그 사실을
     * 아무도 모른다. 표시만 남기고 판정기가 가중을 낮춘다.
     */
    private Duration maxClockPast = Duration.ofMinutes(10);

    /** 기기 시각이 서버보다 앞선 경우. 미래 시각은 뒤처진 것보다 이상하므로 더 좁게 본다. */
    private Duration maxClockFuture = Duration.ofMinutes(2);
}
