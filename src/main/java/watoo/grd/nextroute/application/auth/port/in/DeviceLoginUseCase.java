package watoo.grd.nextroute.application.auth.port.in;

import watoo.grd.nextroute.application.auth.dto.OAuthLoginResult;

/** 기기 익명 계정 발급·재진입·연결. 독립 앱의 유일한 신원 입구다. */
public interface DeviceLoginUseCase {

    /**
     * @param deviceSecret  앱이 만든 비밀값(base64url 43~128자). 서버는 해시만 저장한다.
     * @param currentUserId 유효한 Bearer 로 들어온 사용자. 없으면 null — 있으면 그 계정에 기기를 연결한다.
     * @param clientIp      가입 한도용 출발지 IP
     */
    OAuthLoginResult login(String deviceSecret, Long currentUserId, String clientIp);
}
