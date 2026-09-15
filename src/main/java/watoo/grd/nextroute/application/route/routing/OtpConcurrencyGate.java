package watoo.grd.nextroute.application.route.routing;

import java.util.Optional;

/**
 * OTP 호출을 보호하는 <b>글로벌 동시성 상한</b>. api(검색)와 worker(재탐색)가 합쳐 permit N개만
 * 동시에 OTP 를 호출하도록 조인다. 상한은 프로세스가 아니라 <b>공용 Redis</b> 에 있어 인스턴스를
 * 늘려도 합산 상한이 유지된다.
 *
 * <p><b>best-effort 상한</b>이다 — permit 상태를 공용 {@code allkeys-lru} Redis 에 두어 메모리 압박
 * 시 축출될 수 있다(단일 Redis, 리스크 감수 결정). 하드 안전망은 OTP 컨테이너 {@code mem_limit} 이고,
 * 이 gate 는 "강한 전역 상한"이 아니라 정상 상황의 동시성 조절이다.
 *
 * <p>사용 계약:
 * <pre>{@code
 * Optional<OtpPermit> permit = gate.tryAcquire(deadline, "api");
 * if (permit.isEmpty()) { // 포화 또는 Redis 장애(fail-closed) — OTP 호출 금지 }
 * try { ... OTP 호출 ... } finally { gate.release(permit.get()); }
 * }</pre>
 */
public interface OtpConcurrencyGate {

    /**
     * 요청의 남은 예산 안에서 permit 을 기다린다. 대기는 acquire 단계(permit 보유 전)에서만 일어나고,
     * permit 을 얻으면 그 뒤 OTP 호출은 <b>같은 {@link RoutingDeadline} 의 남은 시간</b>으로 한다
     * (예산 이중소비 금지). Redis 장애 시 <b>fail-closed</b> — 포화와 동일하게 empty 를 돌려준다.
     *
     * @param deadline 이 요청에 허용된 마감. 남은 시간이 0 이 되면 대기를 멈추고 empty 를 돌려준다
     * @param role     메트릭 태그용 호출 역할("api" 또는 "worker"). 저카디널리티 고정값
     * @return permit 을 얻으면 토큰, 예산 안에 못 얻거나 Redis 장애면 empty
     */
    Optional<OtpPermit> tryAcquire(RoutingDeadline deadline, String role);

    /** permit 반납. try/finally 에서 부른다. 이미 만료·반납된 토큰이어도 안전하다(idempotent). */
    void release(OtpPermit permit);
}
