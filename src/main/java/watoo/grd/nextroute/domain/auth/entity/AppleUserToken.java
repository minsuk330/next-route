package watoo.grd.nextroute.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

/**
 * 애플 로그인 사용자의 refresh_token 보관. 탈퇴 시 revoke 호출에 쓴다.
 *
 * <p>{@link AuthIdentity} 에 토큰 칸을 더하지 않고 별도 표로 둔다({@link TossUserToken} 선례).
 * 신원과 토큰은 수명·민감도가 다르고, 토큰은 애플 로그인에만 있는 값이라 공통 신원 표를 오염시킨다.
 *
 * <p>{@code refreshTokenEnc} 는 {@code SecretCipher} 로 암호화한 문자열이다. 평문을 넣지 않는다.
 * 사용자당 한 행(user_id unique) — 재로그인마다 최신 토큰으로 갈아끼운다.
 */
@Entity
@Table(name = "apple_user_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppleUserToken extends BaseEntity {

    @Column(nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false, length = 1024)
    private String refreshTokenEnc;

    /** 애플 sub(providerSubject). 대조·조사용이며 신원 판단에는 쓰지 않는다. */
    private String appleSub;

    @Builder
    public AppleUserToken(Long userId, String refreshTokenEnc, String appleSub) {
        this.userId = userId;
        this.refreshTokenEnc = refreshTokenEnc;
        this.appleSub = appleSub;
    }

    /** 재로그인: 최신 토큰으로 갈아끼운다. */
    public void update(String refreshTokenEnc, String appleSub) {
        this.refreshTokenEnc = refreshTokenEnc;
        this.appleSub = appleSub;
    }
}
