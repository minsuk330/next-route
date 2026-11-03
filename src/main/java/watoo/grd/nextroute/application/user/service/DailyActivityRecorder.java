package watoo.grd.nextroute.application.user.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import watoo.grd.nextroute.domain.user.repository.UserDailyActivityRepository;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 인증된 요청이 들어오면 "이 사용자가 오늘 앱을 썼다"를 하루 한 번 남긴다(사용 통계 DAU).
 *
 * <p>요청 스레드는 막지 않는다: 메모리 집합으로 그날 이미 남긴 사용자를 거르고, 처음일 때만 별도
 * executor 로 INSERT 를 넘긴다. 저장이 실패하거나 큐가 차면 WARN 만 남기고 집합에서 빼서 다음 요청 때
 * 다시 시도한다 — 통계 기록 때문에 사용자 요청이 실패하면 안 된다.
 *
 * <p>여러 인스턴스가 같은 사용자를 동시에 남겨도 DB 의 {@code ON CONFLICT DO NOTHING} 이 하나만 둔다.
 */
@Slf4j
public class DailyActivityRecorder {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 하루 메모리 상한. 넘으면 집합을 비운다(중복 INSERT 는 DB 가 0행으로 흡수). */
    static final int MAX_SEEN_PER_DAY = 50_000;
    private static final Pattern APP_BUILD = Pattern.compile("^[A-Za-z]+/\\d+$");
    private static final int APP_BUILD_MAX = 32;

    private final UserDailyActivityRepository repository;
    private final TaskExecutor executor;
    private final Clock clock;

    private final Set<Long> seenToday = ConcurrentHashMap.newKeySet();
    private volatile LocalDate seenDate;

    public DailyActivityRecorder(UserDailyActivityRepository repository, TaskExecutor executor, Clock clock) {
        this.repository = repository;
        this.executor = executor;
        this.clock = clock;
    }

    public void touch(long userId, String userAgent) {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        if (!today.equals(seenDate)) {
            synchronized (this) {
                if (!today.equals(seenDate)) {
                    seenToday.clear();
                    seenDate = today;
                }
            }
        }
        if (seenToday.size() >= MAX_SEEN_PER_DAY) {
            seenToday.clear();
        }
        if (!seenToday.add(userId)) {
            return;
        }
        String appBuild = appBuild(userAgent);
        try {
            executor.execute(() -> save(today, userId, appBuild));
        } catch (TaskRejectedException e) {
            seenToday.remove(userId);
            log.warn("[DailyActivity] 큐가 가득 차 이번 기록을 건너뜀: userId={}", userId);
        }
    }

    private void save(LocalDate today, long userId, String appBuild) {
        try {
            repository.insertIgnoringDuplicate(today, userId, clock.instant(), appBuild);
        } catch (RuntimeException e) {
            seenToday.remove(userId);
            log.warn("[DailyActivity] 기록 실패(다음 요청 때 재시도): userId={}, cause={}", userId, e.toString());
        }
    }

    /** User-Agent 첫 토큰이 {@code app/7}·{@code Dev/6} 꼴일 때만. 브라우저·스크립트는 null. */
    static String appBuild(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        String first = userAgent.strip().split("\\s+", 2)[0];
        if (!APP_BUILD.matcher(first).matches()) {
            return null;
        }
        return first.length() > APP_BUILD_MAX ? first.substring(0, APP_BUILD_MAX) : first;
    }
}
