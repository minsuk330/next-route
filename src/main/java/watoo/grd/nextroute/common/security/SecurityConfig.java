package watoo.grd.nextroute.common.security;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import watoo.grd.nextroute.application.auth.config.AdminProperties;
import watoo.grd.nextroute.application.auth.port.out.TokenGenerationPort;

/**
 * 무상태(JWT) 보안 설정. 기존 오픈 엔드포인트는 그대로 permitAll로 유지하고,
 * 인증이 필요한 엔드포인트만 명시적으로 authenticated 처리한다.
 * 보호 대상이 늘면 authorizeHttpRequests에 규칙을 추가한다.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtProvider jwtProvider;
    private final AdminProperties adminProperties;
    private final TokenGenerationPort tokenGenerationPort;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 운영 전용(정적 Admin API 키). X-Admin-Key 헤더로만 접근
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // 인증 필요한 엔드포인트(회원 전용)
                        .requestMatchers(HttpMethod.POST, "/api/auth/toss/logout").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
                        .requestMatchers("/api/me").authenticated()
                        .requestMatchers("/api/route/fav/**").authenticated()
                        .requestMatchers("/api/route/my/**").authenticated()
                        .requestMatchers("/api/notifications/**").authenticated()
                        // 앱 OAuth 로그인·challenge 는 인증 전 호출이라 공개다.
                        // 재발급(/api/auth/reissue)도 공개다 — access 가 만료된 뒤 부르는 경로라
                        // Bearer 를 요구할 수 없다. 대신 본문의 refresh 토큰이 신원을 증명한다.
                        // 보호가 필요한 신규 엔드포인트가 생기면 여기에 명시적으로 추가한다 —
                        // 이 설정은 "적어 둔 것만 보호"라 빠뜨리면 익명에 열린다.
                        // 그 외 전부 오픈(공용). 로그인/재발급/콜백 포함
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        (req, res, ex) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .addFilterBefore(new AdminApiKeyFilter(adminProperties.getApiKey()), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new JwtAuthFilter(jwtProvider, tokenGenerationPort), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
