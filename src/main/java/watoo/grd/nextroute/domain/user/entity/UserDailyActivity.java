package watoo.grd.nextroute.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 인증된 사용자가 그날(KST) 앱을 썼다는 사실. 사용자당 하루 1행 — 사용 통계(DAU) 원천.
 *
 * <p>쓰기는 persist 가 아니라 {@code UserDailyActivityRepository.insertIgnoringDuplicate}(ON CONFLICT)다.
 * 엔티티는 탈퇴 벌크 삭제와 스키마(경계 IT 의 ddl-auto)를 위해 있다.
 */
@Entity
@Table(name = "user_daily_activity")
@IdClass(UserDailyActivity.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserDailyActivity {

    @Id
    @Column(name = "activity_date", nullable = false)
    private LocalDate activityDate;

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private User user;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "app_build", length = 32)
    private String appBuild;

    public record Key(LocalDate activityDate, Long userId) implements Serializable {
        public Key() {
            this(null, null);
        }
    }
}
