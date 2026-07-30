package watoo.grd.nextroute.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

/**
 * 사용자. 신원의 기준은 {@code id} 이고, 외부 로그인 수단은 {@code auth_identity} 가 따로 들고 있다.
 *
 * <p>V33 이전에는 {@code tossUserKey} 가 유일한 신원 식별자였다. 지금은 토스 연결끊기 콜백 경로가
 * 아직 쓰는 레거시 미러일 뿐이며, 애플·카카오로 가입한 사용자는 이 값이 비어 있다.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    /**
     * 토스 로그인 사용자 식별키. 앱 OAuth 로 가입하면 비어 있다.
     *
     * <p>신원 조회에 쓰지 않는다({@code auth_identity} 를 본다). 중복 금지는 값이 있을 때만
     * 적용되는 부분 인덱스가 담당한다(V33).
     */
    @Column(unique = true)
    private Long tossUserKey;

    @Builder
    public User(Long tossUserKey) {
        this.tossUserKey = tossUserKey;
    }

    /** 토스 신원이 붙어 있는 사용자인지. 알림 기능이 아직 이 값을 요구한다. */
    public boolean hasTossIdentity() {
        return tossUserKey != null;
    }
}
