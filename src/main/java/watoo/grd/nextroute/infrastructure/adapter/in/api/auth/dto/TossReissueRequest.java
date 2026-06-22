package watoo.grd.nextroute.infrastructure.adapter.in.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class TossReissueRequest {

    @NotBlank
    private String refreshToken;
}
