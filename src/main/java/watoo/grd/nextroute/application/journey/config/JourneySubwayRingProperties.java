package watoo.grd.nextroute.application.journey.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * 지하철 진행 링 설정.
 *
 * <p><b>서버는 진행 숫자를 자주 push 하지 않는다.</b> 실시간 시간 앵커를 만들어 두면 앱이 그
 * 사이를 로컬에서 보간한다. 그래서 여기 있는 값 대부분은 "앱이 얼마나 오래, 어디까지 스스로
 * 채워도 되는가"를 정한다.
 *
 * <p><b>관문 숫자는 전부 잠정값이다.</b> 실기기 관측 전에는 근거가 없어 코드가 아니라 설정에
 * 둔다. 코드에 상수로 박으면 실측 뒤에 고칠 자리가 배포와 묶인다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "journey.subway.ring")
public class JourneySubwayRingProperties {

    /** 기본값 off. 갱신기가 없으면 링은 만들어지지 않고 스냅샷의 안내도 비어 있다. */
    private boolean enabled = false;

    /** 갱신 주기. 원천이 60~90초마다 갱신되므로 이보다 잦을 이유가 없다. */
    private long tickMs = 5000;

    /** 한 tick 에서 볼 최대 고정 수. */
    private int batchSize = 100;

    /**
     * 이동 중 진행률의 상한.
     *
     * <p>도착 전에 100% 를 찍으면 사용자는 이미 내렸어야 한다고 읽는다. 실제 도착 사건이 오기
     * 전에는 여기서 멈춘다.
     */
    private BigDecimal progressTarget = new BigDecimal("0.90");

    /** 다음 역 <b>진입</b>. 도착과 가른다 — 진입은 아직 문이 안 열렸다. */
    private BigDecimal approachProgress = new BigDecimal("0.95");

    /**
     * 앱이 로컬 보간을 이어가도 되는 폭. 관측 시각부터 잰다.
     *
     * <p>길면 죽은 화면이 살아 있는 척하고, 짧으면 링이 자주 멈춘다. 실기기 관측 전에는 근거가
     * 없는 값이라 설정으로 둔다.
     */
    private Duration freshnessWindow = Duration.ofSeconds(120);

    /**
     * ring 키 TTL. <b>반드시 둔다.</b>
     *
     * <p>수명주기 네 접점이 지우지만, 그 경로 중 하나라도 새면 TTL 없는 키가 쌓여
     * {@code allkeys-lru} 가 다른 캐시를 밀어낸다.
     */
    private Duration ttl = Duration.ofMinutes(30);
}
