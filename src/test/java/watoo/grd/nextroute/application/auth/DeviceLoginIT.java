package watoo.grd.nextroute.application.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 기기 익명 계정을 실제 DB·필터·탈퇴까지 한 줄로 확인한다.
 *
 * <p>단위 테스트는 저장소를 흉내 낸다. 여기서는 V62 제약, 발급한 토큰이 보호 경로를 통과하는지, 탈퇴가 기기 신원을
 * 지워 같은 비밀값이 새 계정이 되는지를 본다.
 *
 * <p>기본 프로필 IT 는 로컬 DB·Redis 를 그대로 쓴다. 반복 실행해도 깨지지 않게 비밀값은 매번 새로 만들고, 가입 한도는
 * 크게 덮어쓴다(MockMvc 의 출발지는 늘 127.0.0.1 이다). 트랜잭션을 걸지 않는다 — 필터는 커밋된 세대 번호를 읽는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        "journey.notification.relay.enabled=false",
        "auth.device.signup-limit-per-hour=100000"
})
class DeviceLoginIT {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    private static String newSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private MvcResult device(String secret, String bearer) throws Exception {
        var req = post("/api/auth/device").contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceSecret\":\"" + secret + "\"}");
        if (bearer != null) {
            req.header("Authorization", "Bearer " + bearer);
        }
        return mockMvc.perform(req).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int status(String method, String path, String access) throws Exception {
        var req = "DELETE".equals(method) ? delete(path) : get(path);
        return mockMvc.perform(req.header("Authorization", "Bearer " + access))
                .andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("발급 → 보호 경로 통과 → 재진입은 같은 계정 → 초기화(탈퇴) → 옛 토큰 401, 같은 비밀값은 새 계정")
    void issue_use_reenter_withdraw_reissueAsNewUser() throws Exception {
        String secret = newSecret();

        MvcResult first = device(secret, null);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        long userId = body(first).get("userId").asLong();
        String access = body(first).get("accessToken").asText();
        assertThat(body(first).get("provider").asText()).isEqualTo("DEVICE");

        // 원문이 아니라 해시만 저장됐다.
        String storedSubject = jdbcTemplate.queryForObject(
                "SELECT provider_subject FROM auth_identity WHERE user_id = ? AND provider = 'DEVICE'",
                String.class, userId);
        assertThat(storedSubject).hasSize(64).isNotEqualTo(secret);

        assertThat(status("GET", "/api/route/fav", access)).isEqualTo(200);
        JsonNode me = objectMapper.readTree(mockMvc.perform(get("/api/me")
                .header("Authorization", "Bearer " + access)).andReturn().getResponse().getContentAsString());
        assertThat(me.get("identities")).isEmpty();

        assertThat(body(device(secret, null)).get("userId").asLong()).isEqualTo(userId);

        assertThat(status("DELETE", "/api/auth/me", access)).isEqualTo(204);
        assertThat(status("GET", "/api/route/fav", access)).isEqualTo(401);

        MvcResult again = device(secret, null);
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(again).get("userId").asLong()).isNotEqualTo(userId);
    }

    @Test
    @DisplayName("애플 사용자가 Bearer 로 오면 기존 계정에 기기가 붙고, 이후 Bearer 없이도 그 계정으로 들어온다")
    void appleUser_linksDevice_thenReentersAnonymously() throws Exception {
        long appleUser = jdbcTemplate.queryForObject(
                "INSERT INTO users (created_at, updated_at, token_generation) VALUES (now(), now(), 0) RETURNING id",
                Long.class);
        jdbcTemplate.update("""
                INSERT INTO auth_identity (user_id, provider, provider_subject, linked_at, created_at, updated_at)
                VALUES (?, 'APPLE', ?, now(), now(), now())
                """, appleUser, "apple-it-" + appleUser);
        // 구버전 앱이 들고 있던 애플 세션의 access 토큰.
        String appleAccess = jwt(appleUser);

        String secret = newSecret();
        MvcResult linked = device(secret, appleAccess);
        assertThat(linked.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(linked).get("userId").asLong()).isEqualTo(appleUser);

        assertThat(body(device(secret, null)).get("userId").asLong()).isEqualTo(appleUser);
    }

    @Test
    @DisplayName("다른 계정에 묶인 비밀값을 Bearer 로 가져오면 409")
    void bearerWithOthersSecret_conflict() throws Exception {
        String secret = newSecret();
        device(secret, null);
        long appleUser = jdbcTemplate.queryForObject(
                "INSERT INTO users (created_at, updated_at, token_generation) VALUES (now(), now(), 0) RETURNING id",
                Long.class);
        jdbcTemplate.update("""
                INSERT INTO auth_identity (user_id, provider, provider_subject, linked_at, created_at, updated_at)
                VALUES (?, 'APPLE', ?, now(), now(), now())
                """, appleUser, "apple-it-" + appleUser);

        MvcResult result = device(secret, jwt(appleUser));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(result).get("code").asText()).isEqualTo("DEVICE_ALREADY_LINKED");
    }

    @Autowired watoo.grd.nextroute.common.security.JwtProvider jwtProvider;

    private String jwt(long userId) {
        return jwtProvider.createAccessToken(userId, 0);
    }
}
