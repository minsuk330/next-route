package watoo.grd.nextroute.application.route.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.domain.route.favorite.entity.FavoriteType;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
public class FavoriteRequest {
    @NotNull
    private FavoriteType type;
    @NotBlank
    private String endPlace;
    @NotNull
    private Double ex;
    @NotNull
    private Double ey;
    private LocalDateTime endDate;
}
