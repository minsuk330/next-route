package watoo.grd.nextroute.application.route.dto;

import jakarta.validation.constraints.NotNull;

public record RouteSearchRequest(
        @NotNull Double startX,
        @NotNull Double startY,
        @NotNull Double endX,
        @NotNull Double endY,
        String startName,
        String endName
) {
}
