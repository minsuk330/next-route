package watoo.grd.nextroute.infrastructure.adapter.out.auth;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.auth.config.OAuthProviderProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppleTokenRevokerTest {

    private MockWebServer server;
    private AppleTokenRevoker revoker;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        OAuthProviderProperties props = new OAuthProviderProperties();
        props.getApple().setClientId("com.golbob.nextroute");

        AppleClientSecretJwtBuilder builder = mock(AppleClientSecretJwtBuilder.class);
        when(builder.build()).thenReturn("signed-client-secret");

        RestClient restClient = RestClient.builder().baseUrl(server.url("/").toString()).build();
        revoker = new AppleTokenRevoker(restClient, builder, props);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void revoke_success_sendsRevokeForm() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)); // 본문 없는 200

        revoker.revoke("r-token-xyz");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).endsWith("/auth/revoke");
        String body = req.getBody().readUtf8();
        assertThat(body).contains("client_id=com.golbob.nextroute");
        assertThat(body).contains("client_secret=signed-client-secret");
        assertThat(body).contains("token=r-token-xyz");
        assertThat(body).contains("token_type_hint=refresh_token");
    }

    @Test
    void revoke_httpError_propagates() {
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"invalid_grant\"}"));

        // 어댑터는 예외를 던지고, 상위(AppleRevocationOnWithdrawal)가 삼킨다.
        assertThatThrownBy(() -> revoker.revoke("bad-token"))
                .isInstanceOf(RuntimeException.class);
    }
}
