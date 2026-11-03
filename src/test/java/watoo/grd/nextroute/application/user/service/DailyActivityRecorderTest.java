package watoo.grd.nextroute.application.user.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import watoo.grd.nextroute.domain.user.repository.UserDailyActivityRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DailyActivityRecorderTest {

    private final UserDailyActivityRepository repository = mock(UserDailyActivityRepository.class);
    /** 2026-11-03 23:59:30 KST */
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-03T14:59:30Z"));
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return Clock.fixed(now.get(), zone); }
        @Override public Instant instant() { return now.get(); }
    };

    private DailyActivityRecorder recorder(TaskExecutor executor) {
        return new DailyActivityRecorder(repository, executor, clock);
    }

    @Test
    @DisplayName("같은 날 같은 사용자는 한 번만 남기고, KST 날짜가 바뀌면 다시 남긴다")
    void oncePerKstDay() {
        DailyActivityRecorder recorder = recorder(new SyncTaskExecutor());

        recorder.touch(7L, "app/7 CFNetwork/3860 Darwin/25.6.0");
        recorder.touch(7L, "app/7 CFNetwork/3860 Darwin/25.6.0");
        verify(repository, times(1)).insertIgnoringDuplicate(
                eq(LocalDate.of(2026, 11, 3)), eq(7L), any(), eq("app/7"));

        now.set(Instant.parse("2026-11-03T15:00:30Z")); // 11-04 00:00:30 KST
        recorder.touch(7L, "app/7 CFNetwork/3860 Darwin/25.6.0");
        verify(repository).insertIgnoringDuplicate(eq(LocalDate.of(2026, 11, 4)), eq(7L), any(), eq("app/7"));
    }

    @Test
    @DisplayName("저장이 실패해도 예외를 던지지 않고, 다음 요청 때 다시 시도한다")
    void failureIsSwallowedAndRetried() {
        given(repository.insertIgnoringDuplicate(any(), anyLong(), any(), any()))
                .willThrow(new IllegalStateException("db down")).willReturn(1);
        DailyActivityRecorder recorder = recorder(new SyncTaskExecutor());

        assertThatCode(() -> recorder.touch(7L, null)).doesNotThrowAnyException();
        recorder.touch(7L, null);
        recorder.touch(7L, null);

        verify(repository, times(2)).insertIgnoringDuplicate(any(), eq(7L), any(), isNull());
    }

    @Test
    @DisplayName("큐가 가득 차 거절돼도 예외 없이 건너뛰고, 다음 요청 때 다시 시도한다")
    void rejectedIsRetried() {
        java.util.concurrent.atomic.AtomicBoolean full = new java.util.concurrent.atomic.AtomicBoolean(true);
        TaskExecutor executor = task -> {
            if (full.get()) throw new TaskRejectedException("full");
            task.run();
        };
        DailyActivityRecorder recorder = recorder(executor);

        assertThatCode(() -> recorder.touch(7L, null)).doesNotThrowAnyException();
        verify(repository, never()).insertIgnoringDuplicate(any(), anyLong(), any(), any());

        full.set(false);
        recorder.touch(7L, null);
        verify(repository, times(1)).insertIgnoringDuplicate(any(), eq(7L), any(), isNull());
    }

    @Test
    @DisplayName("앱 빌드는 UA 첫 토큰이 이름/숫자일 때만")
    void appBuildParsing() {
        assertThat(DailyActivityRecorder.appBuild("app/7 CFNetwork/3860.700.2 Darwin/25.6.0")).isEqualTo("app/7");
        assertThat(DailyActivityRecorder.appBuild("Dev/6 CFNetwork/3860.600.12 Darwin/25.5.0")).isEqualTo("Dev/6");
        assertThat(DailyActivityRecorder.appBuild("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)")).isNull();
        assertThat(DailyActivityRecorder.appBuild("okhttp/4.12.0")).isNull();
        assertThat(DailyActivityRecorder.appBuild(null)).isNull();
        assertThat(DailyActivityRecorder.appBuild("  ")).isNull();
    }
}
