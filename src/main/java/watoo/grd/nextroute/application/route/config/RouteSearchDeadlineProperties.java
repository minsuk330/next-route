package watoo.grd.nextroute.application.route.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 경로 검색의 시간 예산. 값은 Step 0 baseline 측정 후 정했다(RESULTS.md).
 *
 * <p>정상 검색은 60ms 수준이므로 3초는 충분히 여유 있는 상한이다. 이 상한의 목적은
 * 평균을 줄이는 게 아니라 <b>tail 을 자르는 것</b> — 상류가 느릴 때 요청 스레드가
 * 무한정 붙잡히지 않게 한다.
 *
 * <p>단계 상한은 "이 단계가 최대 얼마까지 써도 되는가"이고, 실제 timeout 은
 * {@code min(단계 상한, 남은 시간)} 이다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "route.search")
public class RouteSearchDeadlineProperties {

    /** 요청 전체 시간 예산. */
    private long deadlineMs = 3000;

    /** ODsay(필수) 단계 상한. */
    private long odsayMs = 2000;

    /** TMAP 보행 보강(선택) 단계 상한. */
    private long tmapMs = 1200;

    /**
     * 선택 보강을 시작하기 위한 최소 잔여 시간. 이보다 적게 남았으면 호출하지 않고 생략한다.
     * 남은 시간이 거의 없는데 호출을 시작하면 orphan 호출만 만들고 응답은 어차피 못 기다린다.
     */
    private long minRemainingForOptionalMs = 150;
}
