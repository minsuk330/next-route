package watoo.grd.nextroute.infrastructure.adapter.in.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.domain.route.log.repository.RouteSearchEventRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RouteSearchEventRetentionSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-11-26T19:40:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-08-28T19:40:00Z"); // 90일 전
    private final RouteSearchEventRepository repository = mock(RouteSearchEventRepository.class);
    private final RouteSearchEventRetentionScheduler scheduler =
            new RouteSearchEventRetentionScheduler(repository, Clock.fixed(NOW, ZoneOffset.UTC), 90);

    @Test
    @DisplayName("묶음이 가득 차면 이어서, 덜 차면 멈춘다")
    void deletesInBatchesUntilShort() {
        given(repository.deleteOlderThan(eq(CUTOFF), anyInt()))
                .willReturn(RouteSearchEventRetentionScheduler.BATCH, RouteSearchEventRetentionScheduler.BATCH, 12);

        scheduler.purge();

        verify(repository, times(3)).deleteOlderThan(CUTOFF, RouteSearchEventRetentionScheduler.BATCH);
    }

    @Test
    @DisplayName("한 번에 MAX_BATCHES 묶음까지만")
    void capsBatches() {
        given(repository.deleteOlderThan(eq(CUTOFF), anyInt())).willReturn(RouteSearchEventRetentionScheduler.BATCH);

        scheduler.purge();

        verify(repository, times(RouteSearchEventRetentionScheduler.MAX_BATCHES))
                .deleteOlderThan(CUTOFF, RouteSearchEventRetentionScheduler.BATCH);
    }

    @Test
    @DisplayName("DB 오류는 삼키고 다음 날 다시")
    void swallowsFailure() {
        given(repository.deleteOlderThan(eq(CUTOFF), anyInt())).willThrow(new IllegalStateException("down"));

        assertThatCode(scheduler::purge).doesNotThrowAnyException();
    }
}
