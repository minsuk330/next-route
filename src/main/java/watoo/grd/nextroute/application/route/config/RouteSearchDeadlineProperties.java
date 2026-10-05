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

    /**
     * 요청 전체 시간 예산.
     *
     * <p>2026-09-20 에 3000 → 4500. OTP 호출(실측 0.9~2.0s)에 도착예측 보강(최대 1500ms)이 뒤따르는데
     * 3000 이면 보강이 상시로 잘려 값이 빈 채 degraded 만 남는다.
     *
     * <p>2026-10-05 에 4500 → 9500. {@link #otpMs} 를 8000 으로 올리면서 OTP 최대 예산 + 보강 상한(1500)을
     * 담게 맞췄다. 보강은 남은 시간 안에서만 돌므로 정상 검색(OTP 1~2s)의 응답 시간은 그대로다.
     */
    private long deadlineMs = 9500;

    /** ODsay(필수) 단계 상한. */
    private long odsayMs = 2000;

    /**
     * OTP(필수) 호출 예산. OTP planConnection 왕복이 ODsay(2000)보다 느려 전용값을 둔다.
     * OTP 경로는 단일 단계(enricher 없음)라 이 값이 곧 요청 예산 = readTimeout.
     * 근거(2026-09-16 VPS 실측, 경로 5종×12): OTP p99~1.9s/max 2.04s + 앱 역직렬화 오버헤드 + tail 여유.
     *
     * <p>2026-10-05 에 3500 → 8000. 전국 그래프에서 장거리·지방 검색(예: 서울 종로 → 충주)이 3.5s 를 넘겨
     * 504 로 끝났다. 수도권 실측만 근거로 잡은 값이라 전국 범위에는 좁았다. 정상 검색은 예산을 다 쓰지
     * 않으므로 지연이 늘지 않고, 느린 검색만 더 기다린다. 대가는 느린 검색이 permit 을 더 오래 잡는 것 —
     * 동시성 lease 도 함께 올렸다({@link OtpConcurrencyProperties#getLeaseMs()}).
     */
    private long otpMs = 8000;

    /** TMAP 보행 보강(선택) 단계 상한. */
    private long tmapMs = 1200;

    /**
     * 선택 보강을 시작하기 위한 최소 잔여 시간. 이보다 적게 남았으면 호출하지 않고 생략한다.
     * 남은 시간이 거의 없는데 호출을 시작하면 orphan 호출만 만들고 응답은 어차피 못 기다린다.
     */
    private long minRemainingForOptionalMs = 150;
}
