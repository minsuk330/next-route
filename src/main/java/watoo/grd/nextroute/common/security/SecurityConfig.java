package watoo.grd.nextroute.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
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
import watoo.grd.nextroute.application.user.service.DailyActivityRecorder;

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
    /** 사용 통계 기록기. api 에만 있고 플래그로 끌 수 있다 — 없으면 필터를 붙이지 않는다. */
    private final ObjectProvider<DailyActivityRecorder> dailyActivityRecorder;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JsonAuthenticationEntryPoint authenticationEntryPoint,
            JsonAccessDeniedHandler accessDeniedHandler
    ) throws Exception {
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
                        // 계정 탈퇴. 익명이 남의 계정을 지울 수 없도록 인증을 강제한다. 이 줄이 없으면
                        // 아래 permitAll 로 떨어져 열린다(/api/auth/* 대부분은 로그인 전 경로라 공개).
                        .requestMatchers(HttpMethod.DELETE, "/api/auth/me").authenticated()
                        .requestMatchers("/api/me").authenticated()
                        // /api/me 는 정확 경로 매처라 하위 경로(/api/me/push-token 등)를 덮지 않는다.
                        // 이 줄이 없으면 하위 경로가 아래 permitAll 로 떨어져 익명에 열린다.
                        .requestMatchers("/api/me/**").authenticated()
                        .requestMatchers("/api/route/fav/**").authenticated()
                        .requestMatchers("/api/route/my/**").authenticated()
                        .requestMatchers("/api/notifications/**").authenticated()
                        .requestMatchers("/api/journeys/**").authenticated()
                        // 앱 OAuth 로그인·challenge 는 인증 전 호출이라 공개다.
                        // 재발급(/api/auth/reissue)도 공개다 — access 가 만료된 뒤 부르는 경로라
                        // Bearer 를 요구할 수 없다. 대신 본문의 refresh 토큰이 신원을 증명한다.
                        // 보호가 필요한 신규 엔드포인트가 생기면 여기에 명시적으로 추가한다 —
                        // 이 설정은 "적어 둔 것만 보호"라 빠뜨리면 익명에 열린다.
                        // 그 외 전부 오픈(공용). 로그인/재발급/콜백 포함
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(new AdminApiKeyFilter(adminProperties.getApiKey()), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new JwtAuthFilter(jwtProvider, tokenGenerationPort), UsernamePasswordAuthenticationFilter.class);
        DailyActivityRecorder recorder = dailyActivityRecorder.getIfAvailable();
        if (recorder != null) {
            http.addFilterAfter(new DailyActivityFilter(recorder), JwtAuthFilter.class);
        }
        return http.build();
    }

    @Bean
    public JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new JsonAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    public JsonAccessDeniedHandler jsonAccessDeniedHandler(ObjectMapper objectMapper) {
        return new JsonAccessDeniedHandler(objectMapper);
    }
}
