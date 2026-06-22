package watoo.grd.nextroute.infrastructure.adapter.out.api.toss;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.auth.config.TossLoginProperties;
import watoo.grd.nextroute.application.auth.exception.TossLoginException;
import watoo.grd.nextroute.application.auth.port.out.TossLoginPort;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TossLoginAdapterTest {

    private MockWebServer server;
    private TossLoginAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        TossLoginProperties props = new TossLoginProperties();
        props.setBaseUrl(server.url("/").toString());
        props.setDecryptKey(Base64.getEncoder().encodeToString(new byte[32]));
        props.setMaxRetries(1);

        RestClient restClient = RestClient.builder().baseUrl(props.getBaseUrl()).build();
        adapter = new TossLoginAdapter(restClient, props, new TossCryptoService(props));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void generateToken_success() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"resultType":"SUCCESS","success":{
                          "tokenType":"Bearer","accessToken":"acc","refreshToken":"ref",
                          "expiresIn":3599,"scope":"user_name user_phone"}}"""));

        TossLoginPort.TossToken token = adapter.generateToken("authCode", "DEFAULT");

        assertThat(token.accessToken()).isEqualTo("acc");
        assertThat(token.refreshToken()).isEqualTo("ref");
        assertThat(token.expiresIn()).isEqualTo(3599);

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).endsWith("/oauth2/generate-token");
        assertThat(req.getBody().readUtf8()).contains("authCode");
    }

    @Test
    void generateToken_invalidGrant_throws() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"invalid_grant\"}"));

        assertThatThrownBy(() -> adapter.generateToken("expired", "DEFAULT"))
                .isInstanceOf(TossLoginException.class)
                .matches(e -> ((TossLoginException) e).isInvalidGrant());
    }

    @Test
    void getUserInfo_parsesUserKeyAndScope_withFutureScopeValue() {
        // scope에 미정의 값(user_key) 포함되어도 예외 없어야 함
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"resultType":"SUCCESS","success":{
                          "userKey":443731104,
                          "scope":"user_name,user_phone,user_key",
                          "agreedTerms":["terms_tag1","terms_tag2"],
                          "name":null,"phone":null,"birthday":null,"ci":null,
                          "di":null,"gender":null,"nationality":null,"email":null}}"""));

        TossLoginPort.TossUserInfo info = adapter.getUserInfo("acc");

        assertThat(info.userKey()).isEqualTo(443731104L);
        assertThat(info.scope()).contains("user_key");
        assertThat(info.agreedTerms()).containsExactly("terms_tag1", "terms_tag2");
        assertThat(info.name()).isNull();
    }

    @Test
    void getUserInfo_failResult_throws_notInvalidGrant() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"resultType":"FAIL","error":{"errorCode":"INTERNAL_ERROR","reason":"문제 발생"}}"""));

        assertThatThrownBy(() -> adapter.getUserInfo("acc"))
                .isInstanceOf(TossLoginException.class)
                .matches(e -> !((TossLoginException) e).isInvalidGrant());
    }
}
