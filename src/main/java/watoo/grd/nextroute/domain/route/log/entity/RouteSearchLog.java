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

import java.time.LocalDateTime;
import watoo.grd.nextroute.domain.user.entity.User;

@Entity
@Table(name = "route_search_log")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteSearchLog extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id")
  private User userId;

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

  @Column(columnDefinition = "TEXT")
  private String responseJson;

  @Column(nullable = false)
  private LocalDateTime searchedAt;
}
