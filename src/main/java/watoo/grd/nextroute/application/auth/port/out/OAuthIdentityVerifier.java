package watoo.grd.nextroute.application.auth.port.out;

import watoo.grd.nextroute.application.auth.dto.OAuthCredential;
import watoo.grd.nextroute.application.auth.dto.VerifiedIdentity;
import watoo.grd.nextroute.domain.auth.entity.AuthProvider;

/**
 * provider 토큰을 검증해 신원을 돌려준다. 수단마다 확인 방법이 달라 구현을 나눈다.
 *
 * <p><b>클라이언트가 함께 보낸 사용자 정보는 신원 판단에 쓰지 않는다.</b>
 * 서버가 발급 회사에 직접 확인한 결과만 신뢰한다.
 */
public interface OAuthIdentityVerifier {

    AuthProvider provider();

    /**
     * 도장·발급처·대상앱·유효기간·일회용값을 확인하고 신원을 돌려준다.
     *
     * @throws watoo.grd.nextroute.application.auth.exception.OAuthVerificationException
     *         하나라도 통과하지 못했을 때
     */
    VerifiedIdentity verify(OAuthCredential credential);
}
