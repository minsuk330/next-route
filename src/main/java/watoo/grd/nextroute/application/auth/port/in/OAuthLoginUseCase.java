package watoo.grd.nextroute.application.auth.port.in;

import watoo.grd.nextroute.application.auth.dto.OAuthLoginResult;
import watoo.grd.nextroute.application.auth.dto.OAuthCredential;

public interface OAuthLoginUseCase {

    /** 앱 OAuth 로그인. 처음 보는 신원이면 가입까지 겸한다. */
    OAuthLoginResult login(OAuthCredential credential);

    /** 애플 로그인에 쓸 일회용 값을 발급한다. */
    String issueChallenge();
}
