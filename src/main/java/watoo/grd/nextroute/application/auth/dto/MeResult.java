package watoo.grd.nextroute.application.auth.dto;

import watoo.grd.nextroute.domain.auth.entity.AuthProvider;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 로그인한 사용자가 자기 정보를 되읽는 결과.
 *
 * <p>애플은 이름을 최초 1회만 준다. 서버가 {@code auth_identity} 에 넣어 둔 값을 앱이 다시
 * 가져올 수 있는 창구가 여기뿐이다.
 *
 * <p>{@code providerSubject}(회사가 준 사용자 번호)는 싣지 않는다. 앱이 쓸 일이 없고,
 * 신원의 기준은 {@code userId} 다.
 */
public record MeResult(
        long userId,
        String displayName,
        String email,
        List<LinkedIdentity> identities
) {

    /** 이 계정에 붙어 있는 로그인 수단. */
    public record LinkedIdentity(AuthProvider provider, LocalDateTime linkedAt) { }
}
