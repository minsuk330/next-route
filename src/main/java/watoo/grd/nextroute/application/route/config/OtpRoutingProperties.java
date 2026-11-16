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
    /** 검색 응답에 내보내는 경로 수 상한(정렬·중복 제거 뒤). 5→10(2026-10-13, 사용자 요청). */
    private int displayLimit = 10;
    /**
     * 주행 재탐색 후보 수 상한. 엔진 순서 앞에서 자른다. 후보 수가 곧 실시간 평가 횟수라
     * 검색 노출 수({@link #displayLimit})와 따로 둔다.
     */
    private int rerouteLimit = 5;
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
    /**
     * 검색의 고유 경로가 {@link #supplementaryThreshold} 보다 적으면 "지하철만"→"버스만"을 더 묻는다. OTP 는 지배당한 대안을
     * 탐색 단계에서 버려 부평→서울은 1호선 하나만 남는다(2026-10-12, 수단별로 물으면 12개). false 면 본 검색만.
     */
    private boolean supplementaryEnabled = true;
    /**
     * 추가 검색을 부르는 고유 경로 수 기준. 이보다 적을 때만 더 묻는다. 노출 상한({@link #displayLimit})과 따로 둔다 —
     * 노출을 10 으로 올려도 추가 검색은 예전처럼 5 개 미만일 때만(2026-10-13, 사용자 결정).
     */
    private int supplementaryThreshold = 5;
    /** 추가 검색 1회 예산 상한(ms). 남은 OTP 예산과 작은 쪽. */
    private long supplementaryBudgetMs = 2000;
    /**
     * 장소 이름이 역이고 그 역 승강장이 {@link #stationSnapMeters} 안이면 그 승강장의 가상 역 id 로 검색한다
     * ({@code OtpStationResolver}). 가상 역 = 승강장 + 역 출구 지점들이라, 버스는 가장 가까운 출구에서 걷는다
     * (2026-10-12 잠실역→강남역: 좌표 출발은 360 직통 도보 672m+825m). 출구가 들어간 그래프에서만 효과가 있다.
     */
    private boolean stationSnapEnabled = true;
    /** 장소 검색 역 좌표는 승강장에서 6~58m(잠실·강남·홍대입구·서울역 실측). 이름 조건이 있어 150m. */
    private double stationSnapMeters = 150;
    /**
     * 버스 합치기 킬스위치(otp-bus-lane-merge). 켜면 같은 승하차 정류장의 버스 후보를 한 경로로 묶고 그 구간 lane 에
     * 노선을 여럿 싣는다(네이버·카카오 방식). false 면 노선 이름 지문과 lane 1개(옛 동작).
     */
    private boolean busLaneMergeEnabled = true;
    /**
     * 버스 구간마다 OTP {@code nextLegs} 로 물을 다음 편 수. 0 이면 묻지 않는다(묶기는 OTP 후보끼리만). 편 수라서 배차가
     * 잦은 노선 하나가 다 먹을 수 있어 넉넉히 30(2026-11-16 실측: 서울역 5번승강장 노선 4→14, OTP +0.1~0.2s).
     */
    private int alternativeLegs = 30;
    /** 버스 구간 lane 상한(대표 포함). 서울 도착 조회가 lane 마다 순차라 검색 응답 시간을 지킨다. */
    private int maxBusLanes = 6;
    /** 대체 노선 탑승시간 상한 = 대표 탑승시간 × 이 값 + {@link #alternativeRideSlackSeconds}. 실측 노이즈(수원 2-1) 제거. */
    private double alternativeRideFactor = 1.3;
    private int alternativeRideSlackSeconds = 300;
    /**
     * 최단 소요 × 이 값을 넘는 경로는 가장 빠른 {@link #slowRouteKeep} 개만 남긴다(otp-kakao-parity). 0 이하면 끈다.
     * 2026-11-16 운영: 합정→잠실 최단 41분에 91~102분 버스 경로 8개가 목록을 채웠다.
     */
    private double slowRouteFactor = 1.6;
    private int slowRouteKeep = 2;
    /**
     * 철도만 타는 경로가 {@link #railAlternativeThreshold} 개보다 적으면, 가장 좋은 철도 경로의 노선을 하나씩 빼고 철도만 다시
     * 묻는다(otp-kakao-parity). 호출은 노선마다 한 번, {@link #railAlternativeMaxLines} 까지. false 면 옛 "철도만" 추가 검색.
     */
    private boolean railAlternativesEnabled = true;
    private int railAlternativeThreshold = 3;
    private int railAlternativeMaxLines = 2;
    /**
     * 노선을 빼고 다시 물을 때 수단을 제한하지 않는다(true). 뺀 구간을 버스로 잇는 혼합 경로가 같이 나온다 — 카카오맵 상위 목록의
     * 37% 가 버스+지하철 경로다. false 면 철도만 묻는다(옛 동작, 호출당 약 0.6초 빠름).
     */
    private boolean railAlternativeAllModes = true;
    /** 예매 열차(KTX·SRT·무궁화 등 국가철도)를 이 시간(분)보다 짧게 타는 경로는 뺀다 — 도시 안 한두 정거장 이동에 섞인다. 0 이면 끈다. */
    private int intercityRailMinRideMinutes = 40;
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
    public double getSlowRouteFactor() { return slowRouteFactor; }
    public void setSlowRouteFactor(double slowRouteFactor) { this.slowRouteFactor = slowRouteFactor; }
    public int getSlowRouteKeep() { return slowRouteKeep; }
    public void setSlowRouteKeep(int slowRouteKeep) { this.slowRouteKeep = slowRouteKeep; }
    public boolean isRailAlternativesEnabled() { return railAlternativesEnabled; }
    public void setRailAlternativesEnabled(boolean railAlternativesEnabled) { this.railAlternativesEnabled = railAlternativesEnabled; }
    public int getRailAlternativeThreshold() { return railAlternativeThreshold; }
    public void setRailAlternativeThreshold(int railAlternativeThreshold) { this.railAlternativeThreshold = railAlternativeThreshold; }
    public boolean isRailAlternativeAllModes() { return railAlternativeAllModes; }
    public void setRailAlternativeAllModes(boolean railAlternativeAllModes) { this.railAlternativeAllModes = railAlternativeAllModes; }
    public int getRailAlternativeMaxLines() { return railAlternativeMaxLines; }
    public void setRailAlternativeMaxLines(int railAlternativeMaxLines) { this.railAlternativeMaxLines = railAlternativeMaxLines; }
    public int getIntercityRailMinRideMinutes() { return intercityRailMinRideMinutes; }
    public void setIntercityRailMinRideMinutes(int intercityRailMinRideMinutes) { this.intercityRailMinRideMinutes = intercityRailMinRideMinutes; }
    public boolean isBusLaneMergeEnabled() { return busLaneMergeEnabled; }
    public void setBusLaneMergeEnabled(boolean busLaneMergeEnabled) { this.busLaneMergeEnabled = busLaneMergeEnabled; }
    public int getAlternativeLegs() { return alternativeLegs; }
    public void setAlternativeLegs(int alternativeLegs) { this.alternativeLegs = alternativeLegs; }
    public int getMaxBusLanes() { return maxBusLanes; }
    public void setMaxBusLanes(int maxBusLanes) { this.maxBusLanes = maxBusLanes; }
    public double getAlternativeRideFactor() { return alternativeRideFactor; }
    public void setAlternativeRideFactor(double alternativeRideFactor) { this.alternativeRideFactor = alternativeRideFactor; }
    public int getAlternativeRideSlackSeconds() { return alternativeRideSlackSeconds; }
    public void setAlternativeRideSlackSeconds(int alternativeRideSlackSeconds) { this.alternativeRideSlackSeconds = alternativeRideSlackSeconds; }
    public int getRerouteLimit() { return rerouteLimit; }
    public int getSupplementaryThreshold() { return supplementaryThreshold; }
    public void setSupplementaryThreshold(int supplementaryThreshold) { this.supplementaryThreshold = supplementaryThreshold; }
    public void setRerouteLimit(int rerouteLimit) { this.rerouteLimit = rerouteLimit; }
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
    public boolean isSupplementaryEnabled() { return supplementaryEnabled; }
    public void setSupplementaryEnabled(boolean supplementaryEnabled) { this.supplementaryEnabled = supplementaryEnabled; }
    public long getSupplementaryBudgetMs() { return supplementaryBudgetMs; }
    public void setSupplementaryBudgetMs(long supplementaryBudgetMs) { this.supplementaryBudgetMs = supplementaryBudgetMs; }
    public boolean isStationSnapEnabled() { return stationSnapEnabled; }
    public void setStationSnapEnabled(boolean stationSnapEnabled) { this.stationSnapEnabled = stationSnapEnabled; }
    public double getStationSnapMeters() { return stationSnapMeters; }
    public void setStationSnapMeters(double stationSnapMeters) { this.stationSnapMeters = stationSnapMeters; }
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
