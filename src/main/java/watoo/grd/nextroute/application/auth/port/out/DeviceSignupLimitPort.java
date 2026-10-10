package watoo.grd.nextroute.application.auth.port.out;

/**
 * 기기 익명 계정의 <b>새 가입</b> 한도. 같은 출발지에서 계정을 무한히 찍어내는 것을 늦춘다.
 *
 * <p>이미 있는 계정의 재진입·기기 연결은 세지 않는다 — 사용자 행을 새로 만드는 경로만 대상이다.
 */
public interface DeviceSignupLimitPort {

    /**
     * 이 출발지의 가입 한 건을 점유한다.
     *
     * @param clientIp 요청 출발지 IP 문자열(IPv4 또는 IPv6)
     * @return 한도 안이면 true
     */
    boolean tryAcquire(String clientIp);
}
