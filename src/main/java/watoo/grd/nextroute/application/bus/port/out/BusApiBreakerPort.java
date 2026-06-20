package watoo.grd.nextroute.application.bus.port.out;

import java.time.Instant;

/**
 * 서울 버스 API 공유 circuit breaker.
 *
 * <p>error code 7(일일 트래픽 초과 등) 발생 시 collector·search가 함께 읽고 쓰는 차단 상태를
 * 공유 저장소(Redis)에 원자적으로 기록한다. 조회 전용 {@link BusApiBlockStatusPort}를 확장해
 * 기존 스케줄러는 그대로 조회만 쓰고, 어댑터는 {@link #tripUntil(Instant)}로 차단을 건다.
 *
 * <p><b>fail-closed 계약</b>: 공유 저장소 조회 실패 시 {@link #getBlockedUntil()}은 짧은 차단을
 * 반환해 호출을 막는다(상태 불명 시 보수적으로 provider quota 보호).
 */
public interface BusApiBreakerPort extends BusApiBlockStatusPort {

    /** 지정 시각까지 차단을 건다(공유 저장소 기록). */
    void tripUntil(Instant until);
}
