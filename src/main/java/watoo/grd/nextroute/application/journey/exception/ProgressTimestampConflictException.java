package watoo.grd.nextroute.application.journey.exception;

import lombok.Getter;

import java.time.Instant;

/**
 * 같은 관측 시각에 서로 다른 진행 내용이 왔다.
 *
 * <p>같은 시각을 두 번 관측할 수는 없으므로 둘 중 하나는 잘못된 값이다. 어느 쪽이 맞는지 서버가
 * 고를 근거가 없어 조용히 덮어쓰지 않고 알린다. 앱은 최신 스냅샷을 받아 다시 관측하면 된다.
 */
@Getter
public class ProgressTimestampConflictException extends RuntimeException {

    private final Instant observedAt;

    public ProgressTimestampConflictException(Instant observedAt) {
        super("같은 관측 시각에 다른 진행 내용이 왔다: " + observedAt);
        this.observedAt = observedAt;
    }
}
