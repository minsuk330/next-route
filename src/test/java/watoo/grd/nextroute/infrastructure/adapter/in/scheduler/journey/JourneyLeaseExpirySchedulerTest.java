package watoo.grd.nextroute.infrastructure.adapter.in.scheduler.journey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.service.JourneyLeaseExpiryService;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

/** 만료 tick 하나가 실패해도 다음 tick 이 살아 있어야 한다. */
@DisplayName("만료 스케줄러")
class JourneyLeaseExpirySchedulerTest {

    private final JourneyLeaseExpiryService expiryService = mock(JourneyLeaseExpiryService.class);
    private final JourneyLeaseExpiryScheduler scheduler =
            new JourneyLeaseExpiryScheduler(expiryService);

    @Test
    @DisplayName("tick 마다 밀린 만큼 훑는다")
    void delegatesToSweep() {
        given(expiryService.sweep()).willReturn(3);

        scheduler.run();

        then(expiryService).should().sweep();
    }

    @Test
    @DisplayName("실패를 삼킨다 — 다음 tick 에 다시 시도하면 되는 일이다")
    void swallowsFailure() {
        willThrow(new IllegalStateException("DB 연결 없음")).given(expiryService).sweep();

        assertThatCode(scheduler::run).doesNotThrowAnyException();
    }
}
