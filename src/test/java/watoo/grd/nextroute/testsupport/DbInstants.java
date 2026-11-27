package watoo.grd.nextroute.testsupport;

import java.time.Instant;

/**
 * 시각을 Postgres {@code timestamptz} 정밀도(마이크로초)로 맞춘다.
 *
 * <p>Linux JVM 의 {@code Instant.now()} 는 나노초까지 오고, DB 는 마이크로초로 반올림해 저장한다
 * (pgjdbc 가 보낼 때 반올림). 그래서 "메모리의 값 == DB 에서 다시 읽은 값" 을 그대로 비교하면 Linux
 * (CI 러너)에서만 깨진다 — macOS JVM 의 시계는 마이크로초라 로컬에선 안 보인다. 운영의 소유권 확인은
 * SQL 등호({@code WHERE next_evaluation_at = :leaseUntil})라 양쪽이 같은 규칙으로 반올림돼 영향이 없다.
 */
public final class DbInstants {

    private DbInstants() {
    }

    /** DB 가 저장할 값: 마이크로초로 반올림(0.5 는 올림). */
    public static Instant micros(Instant value) {
        long nanos = value.getNano();
        long rounded = (nanos + 500) / 1_000 * 1_000;
        return Instant.ofEpochSecond(value.getEpochSecond()).plusNanos(rounded);
    }
}
