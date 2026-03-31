package watoo.grd.nextroute.domain.route.favorite.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;
import watoo.grd.nextroute.domain.user.entity.User;

import java.time.LocalDateTime;

@Entity
@Table(name = "favorite_route")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FavoriteRoute extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private FavoriteType type;

    @Column(nullable = false)
    private String endPlace;

    @Column(nullable = false)
    private Double ex;

    @Column(nullable = false)
    private Double ey;

    private LocalDateTime endDate;

    @Builder
    public FavoriteRoute(User user, FavoriteType type, String endPlace,
                         Double ex, Double ey, LocalDateTime endDate) {
        this.user = user;
        this.type = type;
        this.endPlace = endPlace;
        this.ex = ex;
        this.ey = ey;
        this.endDate = endDate;
    }
}
