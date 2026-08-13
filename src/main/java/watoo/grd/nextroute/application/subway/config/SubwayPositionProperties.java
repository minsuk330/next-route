package watoo.grd.nextroute.application.subway.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.service.SubwayLineIdMapper;

import java.time.Duration;
import java.util.List;

/**
 * 실시간 <b>위치</b> 수집 설정. 도착정보 수집({@code realtime.subway})과 별개다.
 *
 * <p>주기가 15초인 이유는 실측이다 — 원천은 열차별로 p50 56초·p90 84초마다 갱신된다.
 * 그보다 빨리 물어보면 같은 값을 다시 받는다.
 */
@Slf4j
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

    /**
     * 설정한 노선이 실제로 부를 수 있는 값인지 부팅 때 확인한다.
     *
     * <p>위치 API 는 경로에 호선명을 받으므로 line_id 를 이름으로 바꿀 수 있어야 한다. 바꾸지
     * 못하는 값을 적어 두면 <b>그 노선만 조용히 빠진 채</b> 나머지가 정상으로 보인다. 지하철
     * 안내가 한 노선에서만 안 되는 상태는 로그를 뒤지기 전까지 아무도 모른다.
     *
     * <p>부팅을 막지는 않는다. 이 기능은 기본이 off 이고, 노선 하나의 오타로 서버가 못 뜨는
     * 것은 대가가 더 크다. 대신 켜져 있을 때는 경고를 남기고 그 노선을 대상에서 뺀다.
     */
    @PostConstruct
    void validateLines() {
        List<String> unresolved = lines.stream()
                .filter(lineId -> SubwayLineIdMapper.toLineName(lineId).isEmpty())
                .toList();
        if (unresolved.isEmpty()) {
            return;
        }
        log.error("[SubwayPosition] 호선명을 모르는 line_id 는 수집하지 않는다 — {}", unresolved);
        this.lines = lines.stream()
                .filter(lineId -> SubwayLineIdMapper.toLineName(lineId).isPresent())
                .toList();
    }
}
