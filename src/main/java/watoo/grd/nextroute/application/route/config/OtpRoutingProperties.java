package watoo.grd.nextroute.application.route.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OTP 라우팅 어댑터 설정. graph 재빌드마다 버전 3종을 갱신한다(캐시 키·pinning 근거).
 * 기본값은 P1 산출물(release-manifest.json) 기준.
 */
@Component
@ConfigurationProperties(prefix = "routing.otp")
public class OtpRoutingProperties {

    /** OTP 내부 endpoint base(그래프ql 경로 제외). */
    private String baseUrl = "http://localhost:18080";
    /** planConnection first N. */
    private int first = 5;
    /** 연결 timeout(ms). read timeout 은 호출별 남은 예산으로 결정. */
    private long connectTimeoutMs = 2000;
    private String schemaVersion = "otp-2.9.0-planConnection-v1";
    private String networkDataVersion = "202503";
    /** P1 graphVersion. 재빌드 시 갱신. */
    private String graphVersion = "seoul-bbox-202503-cal20260707_20261108-g15bfe00d18c6";

    /**
     * OTP 경로의 실시간 ETA 합성(P2-E/P2-F 배선) 독립 킬스위치. OTP <b>라우팅</b> 활성
     * ({@code routing.provider.otp-enabled})과 분리 — OTP 경로만 켜고 실시간 대체/재정렬은 끈 채
     * 배포하려면 이 값을 false 로 둔다. {@code route.transfer-arrival.enabled}(ODSAY 와 공유, 배포 기본 true)와
     * AND 로 걸린다. 기본 false: 명시적으로 켜기 전엔 OTP 도 정적만.
     */
    private boolean realtimeEnabled = false;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public int getFirst() { return first; }
    public void setFirst(int first) { this.first = first; }
    public long getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(long connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getNetworkDataVersion() { return networkDataVersion; }
    public void setNetworkDataVersion(String networkDataVersion) { this.networkDataVersion = networkDataVersion; }
    public String getGraphVersion() { return graphVersion; }
    public void setGraphVersion(String graphVersion) { this.graphVersion = graphVersion; }
    public boolean isRealtimeEnabled() { return realtimeEnabled; }
    public void setRealtimeEnabled(boolean realtimeEnabled) { this.realtimeEnabled = realtimeEnabled; }
}
