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

class AppleTokenExchangerTest {

    private MockWebServer server;
    private AppleTokenExchanger exchanger;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        OAuthProviderProperties props = new OAuthProviderProperties();
        props.getApple().setClientId("com.golbob.nextroute");

        AppleClientSecretJwtBuilder builder = mock(AppleClientSecretJwtBuilder.class);
        when(builder.build()).thenReturn("signed-client-secret");

        RestClient restClient = RestClient.builder().baseUrl(server.url("/").toString()).build();
        exchanger = new AppleTokenExchanger(restClient, builder, props);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void exchange_success_returnsRefreshToken_andSendsForm() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"access_token":"a","token_type":"Bearer","expires_in":3600,
                         "refresh_token":"r-token-xyz","id_token":"idt"}"""));

        String refresh = exchanger.exchangeForRefreshToken("auth-code-1");

        assertThat(refresh).isEqualTo("r-token-xyz");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).endsWith("/auth/token");
        String body = req.getBody().readUtf8();
        assertThat(body).contains("grant_type=authorization_code");
        assertThat(body).contains("code=auth-code-1");
        assertThat(body).contains("client_id=com.golbob.nextroute");
        assertThat(body).contains("client_secret=signed-client-secret");
    }

    @Test
    void exchange_noRefreshTokenInResponse_throws() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"a\",\"token_type\":\"Bearer\"}"));

        assertThatThrownBy(() -> exchanger.exchangeForRefreshToken("code"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void exchange_httpError_propagates() {
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"invalid_grant\"}"));

        assertThatThrownBy(() -> exchanger.exchangeForRefreshToken("used-code"))
                .isInstanceOf(RuntimeException.class);
    }
}
