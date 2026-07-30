package watoo.grd.nextroute.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

import java.time.LocalDateTime;

/**
 * 외부 로그인 수단 하나. 사용자({@code users.id})에 붙는 "신분증" 한 장이다.
 *
 * <p>신원의 기준은 {@code users.id} 이고 이 엔티티는 외부 회사가 매긴 번호를 그 사용자에 연결할 뿐이다.
 * {@code users.toss_user_key} 는 연결끊기 콜백 경로가 아직 쓰는 레거시 미러이며 신원 조회에 쓰지 않는다.
 *
 * <p>제약은 DB 인덱스가 소유한다(V33).
 * <ul>
 *   <li>{@code (provider, provider_subject)} — 한 회사의 한 번호는 한 사용자에게만</li>
 *   <li>{@code (user_id, provider)} — 한 사용자가 같은 회사를 두 번 연결 못 함</li>
 *   <li>{@code user_id where provider <> 'TOSS'} — 앱 로그인 수단은 계정당 하나</li>
 * </ul>
 */
@Entity
@Table(name = "auth_identity")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthIdentity extends BaseEntity {

    /** User 연관 대신 id 로 참조한다(도메인 경계 단순화). */
    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuthProvider provider;

    /** 회사가 이 사람에게 매긴 고유 번호. 신원의 전부다. */
    @Column(nullable = false)
    private String providerSubject;

    /** 회사가 준 값. 소유권을 확인한 적 없고 비거나 겹치거나 바뀔 수 있다. 표시용으로만 쓴다. */
    @Column(length = 320)
    private String email;

    /** 애플은 토큰에 이름을 담지 않아 앱이 최초 로그인에서 따로 넘겨준다. 표시용. */
    @Column(length = 100)
    private String displayName;

    @Column(nullable = false)
    private LocalDateTime linkedAt;

    private LocalDateTime lastLoginAt;

    @Builder
    public AuthIdentity(Long userId, AuthProvider provider, String providerSubject,
                        String email, String displayName, LocalDateTime linkedAt) {
        this.userId = userId;
        this.provider = provider;
        this.providerSubject = providerSubject;
        this.email = email;
        this.displayName = displayName;
        this.linkedAt = linkedAt;
        this.lastLoginAt = linkedAt;
    }

    public void touchLogin(LocalDateTime now) {
        this.lastLoginAt = now;
    }

    /**
     * 표시용 정보를 채운다. 이미 값이 있으면 덮어쓰지 않는다.
     *
     * <p>애플은 이름·이메일을 최초 인증 1회에만 준다. 두 번째 로그인부터는 앱이 null 을 보내므로,
     * 덮어쓰면 처음 받아 둔 값이 지워진다.
     */
    public void fillProfileIfAbsent(String email, String displayName) {
        if (this.email == null && email != null && !email.isBlank()) {
            this.email = email;
        }
        if (this.displayName == null && displayName != null && !displayName.isBlank()) {
            this.displayName = displayName;
        }
    }
}
