package watoo.grd.nextroute.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

  private static final String BEARER_SCHEME = "bearerAuth";

  @Bean
  public OpenAPI customOpenAPI() {
    return new OpenAPI()
        .info(new Info().title("제때"))
        .servers(List.of(
            new Server().url("http://localhost:8080").description("local"),
            new Server().url("https://api.refit-100.site").description("prod"))
        )
        // Swagger UI "Authorize" 버튼: JWT access 토큰 입력 → 보호 엔드포인트 호출에 자동 첨부.
        // 공용(permitAll) 엔드포인트에는 무해.
        .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
        .components(new Components().addSecuritySchemes(BEARER_SCHEME,
            new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")));
  }

}
