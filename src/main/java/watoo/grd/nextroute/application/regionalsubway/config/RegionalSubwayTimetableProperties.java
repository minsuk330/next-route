package watoo.grd.nextroute.application.regionalsubway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 지방 지하철 시간표 적재(docs/plan/subway-regional-timetable). {@code enabled} 는 worker 스케줄러만 본다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "regional-subway.timetable")
public class RegionalSubwayTimetableProperties {

    private boolean enabled = false;

    /** 적재할 노선 코드. 원천 문제가 생긴 노선을 배포 없이 빼려고 둔다. */
    private Set<String> lines = new LinkedHashSet<>(List.of("BS1", "BS2", "BS3", "BS4", "DG1", "DG2", "DG3", "DJ1", "GJ1"));

    /** 원격 원천 호출 사이 간격(ms). TAGO·부산은 같은 data.go.kr 키를 도착 조회와 나눠 쓴다. */
    private long callIntervalMs = 200;

    /** 일시 오류(시간 초과, TAGO 99) 재시도 횟수. */
    private int maxRetries = 3;

    /** 재시도 간격 기준(ms). n 번째 재시도는 n 배를 기다린다. */
    private long retryBackoffMs = 1000;

    /** 출발 표가 비어 있을 때 부팅 직후 한 번 적재한다. */
    private boolean loadOnEmpty = true;
}
