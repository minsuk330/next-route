package watoo.grd.nextroute.domain.route.log.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;
import watoo.grd.nextroute.domain.user.entity.User;

import java.time.LocalDateTime;

/**
 * 사용자별 경로 검색 이력. 목록·삭제(PR2) 조회용 엔티티다.
 *
 * <p>쓰기는 이 엔티티의 persist 가 아니라 native upsert(ON CONFLICT)로 한다
 * ({@code RouteSearchHistoryRepository.upsert}). 좌표(startx/starty/endx/endy)당 1행으로
 * collapse 되며 재검색 시 searched_at·이름이 갱신된다.
 *
 * <p>컬럼 매핑은 기존 {@link RouteSearchLog} 와 동일한 Hibernate 기본 네이밍을 따른다
 * (startX→startx, pathCount→path_count). 삭제는 물리삭제이므로 BaseEntity 의 deletedAt 은 쓰지 않는다.
 */
@Entity
@Table(name = "route_search_history")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteSearchHistory extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private Double startX;

    @Column(nullable = false)
    private Double startY;

    @Column(nullable = false)
    private Double endX;

    @Column(nullable = false)
    private Double endY;

    private String startName;

    private String endName;

    private Integer pathCount;

    private Integer searchType;

    @Column(nullable = false)
    private LocalDateTime searchedAt;
}
