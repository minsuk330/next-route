package watoo.grd.nextroute.application.bus.port.out;

/**
 * 검색용 버스 API provider quota 게이트.
 *
 * <p>검색·collector가 동일 provider key를 공유하므로, 검색 트래픽이 collector·학습데이터 수집의
 * 일일 quota를 잠식하지 않도록 검색 몫(reserved)을 엔드포인트별로 분리한다. Redis 원자 카운터로
 * 일 단위(KST) 사용량을 추적해 다중 인스턴스 간 공유한다. collector 몫은 기존 in-memory budget이
 * 담당하고, provider 총량은 (collector reserved + search reserved) ≤ provider 일일 한도로 보호한다.
 *
 * <p><b>fail-closed</b>: 공유 저장소 장애 시 {@code false}를 반환해 호출을 막는다.
 */
public interface BusApiQuotaPort {

    /**
     * 검색 호출 1건의 quota를 원자적으로 점유한다.
     * @return 점유 성공(호출 허용)이면 true, reserved 소진·저장소 장애면 false(차단).
     */
    boolean tryAcquireSearch(Endpoint endpoint);

    enum Endpoint { ARRIVAL, POSITION }
}
