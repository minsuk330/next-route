package watoo.grd.nextroute.application.arrival.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;

import static org.assertj.core.api.Assertions.assertThat;

/** 경기 공급자 하루 한도 10,000 을 공개 조회 몫(gg-arrival)과 재탐색 버킷이 나눠 쓴다 — 기본값의 합이 한도를 넘으면 안 된다. */
class RegionalBusQuotaBudgetTest {

    private static final int GYEONGGI_PROVIDER_DAILY_LIMIT = 10_000;

    @Test
    @DisplayName("공개 조회 몫 + 재탐색 버킷 ≤ 공급자 일일 한도")
    void defaultsFitTheProviderLimit() {
        int shared = new RegionalBusSharedProperties().getGyeonggiDailyLimit();
        int reroute = new JourneyRerouteProperties().getBoard().getQuotaGyeonggi();

        assertThat(shared + reroute).isLessThanOrEqualTo(GYEONGGI_PROVIDER_DAILY_LIMIT);
    }
}
