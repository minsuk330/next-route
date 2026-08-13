package watoo.grd.nextroute.application.subway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 실시간 <b>위치</b> 수집 설정. 도착정보 수집({@code realtime.subway})과 별개다.
 *
 * <p>주기가 15초인 이유는 실측이다 — 원천은 열차별로 p50 56초·p90 84초마다 갱신된다.
 * 그보다 빨리 물어보면 같은 값을 다시 받는다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "realtime.subway.position")
public class SubwayPositionProperties {

    /** 기본값 off. 외부 API 를 상시로 부르는 기능은 운영이 고른 시점에 켠다. */
    private boolean enabled = false;

    /**
     * 수집 대상 노선(line_id). <b>자동 발견하지 않는다.</b>
     *
     * <p>자동이면 서울이 노선을 늘리는 순간 우리가 모르는 사이 호출량이 늘고, 지원하지 않는
     * 노선까지 추적한다. 늘리는 것은 배포 결정이어야 한다.
     */
    private List<String> lines = List.of();

    /** 운행시간 시작. 도착정보 수집기와 같은 규칙. */
    private String activeHoursStart = "04:30";

    /** 운행시간 끝(다음 날). */
    private String activeHoursEnd = "01:00";

    /**
     * 원천 수신 지연의 상한. 사건 시각 구간의 <b>하한을 과거로</b> 넓히는 데 쓴다.
     *
     * <p>{@code recptnDt} 는 "최종수신시간"이라 실제 사건은 그보다 앞이다. 실측 지연은
     * 56~153초였다 — 그 산포를 다 재기 전까지 보수적으로 잡는다.
     */
    private Duration sourceLagBudget = Duration.ofSeconds(150);

    /** 관측을 "신선하다"고 볼 상한. {@code fetchedAt - recptnDt} 가 이보다 크면 전이에 쓰지 않는다. */
    private Duration freshnessWindow = Duration.ofMinutes(5);

    /** 위치·사건 로그를 남겨 두는 시간. 다음 역 정차 확인(L4)까지 덮어야 한다. */
    private Duration logTtl = Duration.ofMinutes(15);
}
