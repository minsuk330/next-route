package watoo.grd.nextroute.application.bus.port.out;

/**
 * 검색용 버스 API provider quota 게이트.
 *
 * <p>검색·collector가 동일 provider key를 공유하므로, 검색 트래픽이 collector·학습데이터 수집의
 * 일일 quota를 잠식하지 않도록 검색 몫(reserved)을 엔드포인트별로 분리한다. Redis 원자 카운터로
 * 일 단위(KST) 사용량을 추적해 다중 인스턴스 간 공유한다. collector 몫도 이름 붙은 Redis 버킷
 * ({@code collector-arrival}, {@code collector-position})으로 세어 서버 수와 무관하게 하나다. provider 총량은
 * (collector reserved + search reserved + 재탐색·감시자 버킷) ≤ provider 일일 한도로 보호한다.
 *
 * <p><b>fail-closed</b>: 공유 저장소 장애 시 {@code false}를 반환해 호출을 막는다.
 */
public interface BusApiQuotaPort {

    /**
     * 검색 호출 1건의 quota를 원자적으로 점유한다.
     * @return 점유 성공(호출 허용)이면 true, reserved 소진·저장소 장애면 false(차단).
     */
    boolean tryAcquireSearch(Endpoint endpoint);

    /**
     * 이름 붙은 일일 버킷에서 한 번을 가져간다(#264 PR-4 재탐색 전용 경기·TAGO 도착 조회). 사용자 화면용
     * 검색 할당량과 섞지 않는다 — 재탐색이 다 써서 도착 정보 화면이 막히면 안 된다.
     *
     * @param dailyLimit 0 이하이면 항상 거절
     */
    boolean tryAcquire(String bucket, int dailyLimit);

    /**
     * 서울 검색 몫의 끝점. {@code STATION} 은 정류장 단위 도착({@code stationinfo/getStationByUid}, #577) — 공급자
     * 쪽에서도 도착({@code arrive})과 다른 서비스라 몫과 차단기를 따로 둔다.
     */
    enum Endpoint { ARRIVAL, POSITION, STATION }
}
