package watoo.grd.nextroute.application.route.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * OTP 라우팅 어댑터 설정. graph 재빌드마다 버전 3종을 갱신한다(캐시 키·pinning 근거).
 * 기본값은 P1 산출물(release-manifest.json) 기준.
 */
@Component
@ConfigurationProperties(prefix = "routing.otp")
public class OtpRoutingProperties {

    /** OTP 내부 endpoint base(그래프ql 경로 제외). */
    private String baseUrl = "http://localhost:18080";
    /**
     * planConnection first N = OTP 에 요청하는 <b>후보 수</b>(노출 수 아님). 앱이 이 중에서
     * 같은 노선 반복을 묶고 일반화비용 순으로 {@link #displayLimit} 개를 고른다({@code ItineraryRanker}).
     */
    private int first = 20;
    /** 응답에 내보내는 경로 수 상한. 검색은 정렬 뒤, 재탐색은 엔진 순서 앞에서 자른다. */
    private int displayLimit = 5;
    /**
     * OTP 탐색 창. null 이면 보내지 않아 OTP 가 동적으로 잡는다(실측 1h10m~2h10m). 창이 길면 결과가
     * "출발편 타임라인"이 돼 같은 노선이 반복되고 OTP 계산도 길어진다(30분: 1.4s→0.7s, 2026-10-12).
     */
    private Duration searchWindow = Duration.ofMinutes(30);
    /**
     * 버스 탑승시간 가중치(OTP {@code reluctance}). GTFS 버스 탑승시간이 실제보다 10~30% 짧아(ODsay 대비,
     * 지하철은 ±2분) 어떤 기준으로 줄 세워도 버스가 이긴다 → 비용에서만 보정한다(표시 시간은 그대로, #386).
     * 1.0 이면 수단 목록을 보내지 않아 옛 요청과 같다.
     */
    private double busReluctance = 1.3;
    /** 검색 후보 정렬 킬스위치. false 면 엔진 순서(도착시각) 앞 {@link #displayLimit} 개. */
    private boolean rankingEnabled = true;
    /** 연결 timeout(ms). read timeout 은 호출별 남은 예산으로 결정. */
    private long connectTimeoutMs = 2000;
    private String schemaVersion = "otp-2.10.0-planConnection-v1";
    private String networkDataVersion = "202503";
    /** graphVersion. 재빌드 시 갱신. shadow 배포 P2(2.10 H+F, serialization id 276) 산출. */
    private String graphVersion = "seoul-bbox-202503-cal20260707_20261108-otp276-g4e098383";

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
    public int getDisplayLimit() { return displayLimit; }
    public void setDisplayLimit(int displayLimit) { this.displayLimit = displayLimit; }
    public Duration getSearchWindow() { return searchWindow; }
    public void setSearchWindow(Duration searchWindow) { this.searchWindow = searchWindow; }
    public double getBusReluctance() { return busReluctance; }
    public void setBusReluctance(double busReluctance) { this.busReluctance = busReluctance; }
    public boolean isRankingEnabled() { return rankingEnabled; }
    public void setRankingEnabled(boolean rankingEnabled) { this.rankingEnabled = rankingEnabled; }
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
