package watoo.grd.nextroute.application.route.dto;

import lombok.Builder;
import lombok.Getter;
import watoo.grd.nextroute.domain.route.favorite.entity.FavoriteRoute;
import watoo.grd.nextroute.domain.route.favorite.entity.FavoriteType;

import java.time.LocalDateTime;

@Getter
@Builder
public class FavoriteResponse {
    private Long id;
    private FavoriteType type;
    private String endPlace;
    private Double ex;
    private Double ey;
    private LocalDateTime endDate;

    public static FavoriteResponse from(FavoriteRoute entity) {
        return FavoriteResponse.builder()
                .id(entity.getId())
                .type(entity.getType())
                .endPlace(entity.getEndPlace())
                .ex(entity.getEx())
                .ey(entity.getEy())
                .endDate(entity.getEndDate())
                .build();
    }
}
