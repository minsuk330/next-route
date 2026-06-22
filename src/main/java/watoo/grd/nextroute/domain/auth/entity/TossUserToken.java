package watoo.grd.nextroute.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

import java.time.LocalDateTime;

/** 토스 로그인 access/refresh 토큰의 서버측 보관. unlink/재발급에 사용. */
@Entity
@Table(name = "toss_user_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TossUserToken extends BaseEntity {

    @Column(nullable = false, unique = true)
    private Long userKey;

    @Column(nullable = false, length = 2048)
    private String accessToken;

    @Column(nullable = false, length = 2048)
    private String refreshToken;

    private LocalDateTime accessExpiresAt;
    private LocalDateTime refreshExpiresAt;

    @Builder
    public TossUserToken(Long userKey, String accessToken, String refreshToken,
                         LocalDateTime accessExpiresAt, LocalDateTime refreshExpiresAt) {
        this.userKey = userKey;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.accessExpiresAt = accessExpiresAt;
        this.refreshExpiresAt = refreshExpiresAt;
    }

    public void update(String accessToken, String refreshToken,
                       LocalDateTime accessExpiresAt, LocalDateTime refreshExpiresAt) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.accessExpiresAt = accessExpiresAt;
        this.refreshExpiresAt = refreshExpiresAt;
    }
}
