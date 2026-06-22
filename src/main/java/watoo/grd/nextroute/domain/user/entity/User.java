package watoo.grd.nextroute.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

/** 사용자. 신원은 토스 로그인 사용자 식별키(tossUserKey)로만 식별한다. */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    /** 토스 로그인 사용자 식별키(앱 단위 unique). 유일한 신원 식별자. */
    @Column(unique = true, nullable = false)
    private Long tossUserKey;

    @Builder
    public User(Long tossUserKey) {
        this.tossUserKey = tossUserKey;
    }
}
