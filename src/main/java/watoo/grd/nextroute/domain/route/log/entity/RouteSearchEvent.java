package watoo.grd.nextroute.domain.route.log.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.domain.user.entity.User;

import java.time.Instant;

/**
 * 경로 검색 한 번(V79). 사용 퍼널의 첫 단계. 좌표·장소명은 없다(위치정보를 쌓지 않는다).
 *
 * <p>쓰기는 {@code RouteSearchEventRepository.insert}(native)다. 엔티티는 탈퇴 벌크 삭제와
 * 스키마(경계 IT 의 ddl-auto)를 위해 있다.
 */
@Entity
@Table(name = "route_search_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteSearchEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "search_session_id", length = 64)
    private String searchSessionId;

    @Column(name = "user_id")
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private User user;

    @Column(name = "searched_at", nullable = false)
    private Instant searchedAt;

    @Column(name = "outcome", length = 20)
    private String outcome;

    @Column(name = "path_count", nullable = false)
    private int pathCount;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;
}
