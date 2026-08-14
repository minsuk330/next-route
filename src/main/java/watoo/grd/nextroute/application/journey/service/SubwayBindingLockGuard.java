package watoo.grd.nextroute.application.journey.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneySubwayBindingProperties;

/**
 * 사용자 요청이 고정 행을 잠글 때 <b>얼마나 기다릴지</b>의 상한을 건다.
 *
 * <h2>왜 JPA 힌트가 아닌가</h2>
 *
 * <p>PostgreSQL 에는 {@code FOR UPDATE WAIT n} 이 없다. Hibernate 는
 * {@code jakarta.persistence.lock.timeout} 의 임의 값을 문장으로 옮기지 못하고 그냥
 * {@code for update} 로 떨어뜨린다 — 0({@code NOWAIT})과 -2({@code SKIP LOCKED})만 문장이 된다.
 * 그래서 설정만 적어 두면 <b>상한이 있다고 믿는 채 무한정 기다린다.</b>
 *
 * <p>대신 트랜잭션 안에서 {@code SET LOCAL lock_timeout} 을 건다. {@code LOCAL} 이라 이 트랜잭션이
 * 끝나면 원래대로 돌아가고, 커넥션 풀이 그 값을 다음 요청에 흘리지 않는다.
 *
 * <h2>넘기면 어떻게 되나</h2>
 *
 * <p>PostgreSQL 이 {@code 55P03 lock_not_available} 을 던지고 Spring 이
 * {@code PessimisticLockingFailureException} 으로 옮긴다. 부르는 쪽은 그것을 <b>재시도 가능한
 * 실패</b>로 답한다 — 앱이 같은 관측을 다시 보내는 것은 정상이고 감사행 유일성이 중복을 흡수한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubwayBindingLockGuard {

    /** 0 이면 "무한 대기"라 상한이 없는 것과 같다. 1ms 아래로는 내리지 않는다. */
    private static final long MIN_MILLIS = 1L;

    /** 사람이 기다릴 수 있는 상한. 이보다 크면 설정 실수로 본다. */
    private static final long MAX_MILLIS = 60_000L;

    private final EntityManager entityManager;
    private final JourneySubwayBindingProperties properties;

    /**
     * 지금 트랜잭션의 잠금 대기 상한을 건다. <b>반드시 잠금 조회보다 먼저 부른다.</b>
     *
     * <p>트랜잭션 밖에서 부르면 PostgreSQL 이 경고만 남기고 아무 일도 하지 않는다.
     */
    public void applyLockTimeout() {
        long millis = Math.min(MAX_MILLIS,
                Math.max(MIN_MILLIS, properties.getLockTimeout().toMillis()));
        // 값이 숫자로 좁혀진 뒤에만 문장에 들어간다. SET 은 파라미터 바인딩을 받지 않는다.
        entityManager.createNativeQuery("SET LOCAL lock_timeout = " + millis).executeUpdate();
    }
}
