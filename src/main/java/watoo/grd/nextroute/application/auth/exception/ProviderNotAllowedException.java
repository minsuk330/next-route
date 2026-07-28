package watoo.grd.nextroute.application.auth.exception;

import watoo.grd.nextroute.domain.auth.entity.AuthProvider;

/**
 * 공용 로그인 API 로 받을 수 없는 수단. 400 으로 나간다.
 *
 * <p>토스가 여기 걸린다. 미니앱 안에서만 인가코드를 얻을 수 있어 앱에서는 반드시 실패하므로,
 * 선택지로 노출하지 않는다.
 */
public class ProviderNotAllowedException extends RuntimeException {

    public ProviderNotAllowedException(AuthProvider provider) {
        super("앱 로그인에 쓸 수 없는 수단: " + provider);
    }
}
