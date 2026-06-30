package watoo.grd.nextroute.application.bus.port.out;

import java.time.Instant;

/**
 * 서울 버스 API endpoint별 circuit breaker.
 *
 * <p>error code 7(일일 트래픽 초과 등) 발생 시 ARRIVAL/POSITION별 limit count를 기록한다.
 * 같은 endpoint에서 하루 3회 limit이 확인되면 해당 endpoint만 다음 KST 자정까지 차단한다.
 *
 * <p><b>fail-closed 계약</b>: 공유 저장소 조회 실패 시 {@link #getBlockedUntil(BusApiQuotaPort.Endpoint)}은 짧은 차단을
 * 반환해 호출을 막는다(상태 불명 시 보수적으로 provider quota 보호).
 */
public interface BusApiBreakerPort extends BusApiBlockStatusPort {

    /**
     * endpoint별 API limit 관측을 기록한다.
     * 하루 3회 이상 관측되면 해당 endpoint만 지정 시각까지 차단한다.
     */
    LimitDecision recordApiLimit(BusApiQuotaPort.Endpoint endpoint, Instant until);

    record LimitDecision(
            BusApiQuotaPort.Endpoint endpoint,
            int count,
            boolean blocked,
            Instant blockedUntil
    ) {}
}
