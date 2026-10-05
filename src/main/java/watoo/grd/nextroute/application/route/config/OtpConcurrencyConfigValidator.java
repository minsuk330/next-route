package watoo.grd.nextroute.application.route.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;

/**
 * OTP 동시성 gate 의 lease TTL 이 실제 호출 예산보다 큰지 기동 시 강제한다.
 *
 * <p>lease 가 유효 호출 예산보다 작으면, 아직 OTP 를 호출 중인 permit 이 ZSET score 만료로
 * 회수·재할당되어 상한이 무너진다(N 초과). 예산을 lease 위로 올리는 <b>미래의 오설정</b>을
 * 코드로 막는다.
 *
 * <p>OTP 호출 예산은 api 검색과 worker 남은경로 검색 모두 {@code route.search.otp-ms} 다
 * ({@code RemainingRouteSearchService} 도 넘겨받은 budget 이 아니라 otpMs 로 OTP 를 부른다).
 * 그래서 양쪽 예산에 otpMs 를 넣는다. 예전에는 api 예산으로 odsayMs 를 봐서, otpMs 를 lease 위로
 * 올려도 기동 검증을 통과했다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OtpConcurrencyConfigValidator {

    private final OtpConcurrencyProperties concurrency;
    private final RouteSearchDeadlineProperties routeSearch;
    private final JourneyRerouteProperties reroute;

    @PostConstruct
    void validate() {
        long otpBudget = routeSearch.getOtpMs();
        long apiBudget = Math.max(routeSearch.getOdsayMs(), otpBudget);
        long workerBudget = Math.max(reroute.getRemainingPath().getSearchBudgetMs(), otpBudget);
        long maxBudget = Math.max(apiBudget, workerBudget);
        long required = maxBudget + concurrency.getSafetyMarginMs();

        if (concurrency.getLeaseMs() < required) {
            throw new IllegalStateException(String.format(
                    "routing.otp.concurrency.lease-ms(%d) 는 최대 호출 예산 + 여유(%d = max(api %d, worker %d) + %d) 이상이어야 한다 "
                            + "— 작으면 유효 호출 permit 이 만료·재할당되어 동시성 상한이 무너진다",
                    concurrency.getLeaseMs(), required, apiBudget, workerBudget, concurrency.getSafetyMarginMs()));
        }
        if (concurrency.getPermits() <= 0) {
            throw new IllegalStateException(
                    "routing.otp.concurrency.permits 는 1 이상이어야 한다: " + concurrency.getPermits());
        }
        log.info("[OtpConcurrency] gate 설정 검증 통과 — permits={}, leaseMs={} (요구 최소 {}=max(api {}, worker {})+{})",
                concurrency.getPermits(), concurrency.getLeaseMs(), required, apiBudget, workerBudget,
                concurrency.getSafetyMarginMs());
    }
}
