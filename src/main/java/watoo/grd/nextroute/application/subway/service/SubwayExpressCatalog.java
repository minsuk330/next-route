package watoo.grd.nextroute.application.subway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 급행 정차 패턴 카탈로그({@code subway/express-catalog.json}, subway-express D7). 급행 정차역의 정답 원천이다 — OTP 는
 * 낡아서(KTDB) 정답으로 삼지 않는다.
 *
 * <p>{@link SubwayDoorSideIndex}·{@link SubwayFastAlightIndex} 처럼 처음 쓸 때 번들 파일을 읽는다. 역 이름은 그때
 * {@link SubwayLineStationIndex#candidates} 로 역 id 에 잇는다. <b>이름 하나라도 못 이은 서비스는 끄고 경고를 남긴다</b> —
 * 틀린 역에 이은 패턴은 지나치는 역을 정차로, 서는 역을 통과로 만든다. 꺼진 서비스의 열차는 해석 실패(추적 불가)로
 * 가서 안전하다.
 *
 * <p><b>역 마스터가 아직 비었으면 끄지 않는다</b>(subway-express PR-S3). 그 노선 역 목록이 비어 있으면 "이름이 틀렸다"가
 * 아니라 "아직 못 읽었다"라서, 그 노선 서비스는 보류하고 {@link #RETRY_INTERVAL} 뒤 호출에서 다시 잇는다. 모든 노선을
 * 이은 뒤에는 다시 풀지 않는다(volatile 읽기 하나). 역 목록이 있는데 이름이 없으면 예전처럼 영구히 끈다.
 * 열차 종류 판정에 쓰는 번호 규칙({@link #isTrainNoAuthoritativeExpress}·{@link #isAllStopTrain}·{@link #hasServices})은
 * 역 id 가 필요 없어 파일 정의를 바로 본다 — 역 마스터 적재 전에도 4호선 44xx 가 급행이다.
 *
 * <p><b>열차 고르기 규칙</b>({@link #resolve}): 같은 노선 서비스 중 ① {@code trainNos} ② {@code trainNoRanges}
 * (제외 번호 빼고) ③ {@code within}(현재 역, 모르면 행선지가 그 목록 안) ④ 종류만({@code kind}) 순서로, 처음 답이 나온
 * 단계에서 정확히 하나일 때만 답한다. 호출부는 이미 급행으로 판정한 열차만 넘긴다({@link SubwayTrainKindClassifier}).
 */
@Slf4j
@Component
public class SubwayExpressCatalog {

    static final String RESOURCE = "subway/express-catalog.json";

    private static final ObjectMapper OM = new ObjectMapper();
    private static final DestinationNormalizer NORMALIZER = new DestinationNormalizer();

    /** 각역 구간({@code allStops})을 완행 순서로 펼친다. {@code SubwayLineOrder::allStopPath} 가 맞는다. */
    @FunctionalInterface
    public interface AllStopPath {
        /** @return from → to 를 포함한 진행 순서 역 id. 모르면 빈 목록 */
        List<String> path(String lineId, String direction, String fromStationId, String toStationId);
    }

    /**
     * 해석된(역 id 에 이은) 서비스 하나.
     *
     * @param listedDirection      카탈로그 {@code stops} 순서로 달리는 실시간 방향
     * @param trainNoAuthoritative 실시간이 '일반'으로 와도 번호만으로 급행인지(4호선 K44xx)
     */
    public record ExpressService(String id, String lineId, String label, String listedDirection,
                                 String catalogVersion, LocalDate effectiveFrom, List<String> source,
                                 boolean trainNoAuthoritative) {
    }

    // ── 파일 모양 ─────────────────────────────────────────────────────────

    /** @param allStopTrains 급행으로 표시되지만 모든 역에 서는 열차(서비스 아님, 분류기가 NORMAL 로 본다) */
    record Catalog(String version, List<Definition> services, List<AllStopRule> allStopTrains) {
    }

    /** 노선 하나의 '표시만 급행' 번호 규칙. {@link Match} 의 번호 부분만 쓴다. */
    record AllStopRule(String lineId, Match match) {
    }

    record Definition(String id, String lineId, String label, String listedDirection, Match match,
                      List<Element> elements, List<String> source, LocalDate effectiveFrom) {
    }

    /** @param ranges [from, to] 포함 범위(숫자 번호) */
    record Match(String kind, Set<String> trainNos, List<int[]> ranges, Set<String> excludeTrainNos,
                 List<String> within, boolean trainNoAuthoritative) {

        boolean byTrainNos() {
            return !trainNos.isEmpty();
        }

        boolean byRanges() {
            return !ranges.isEmpty();
        }

        boolean byWithin() {
            return !within.isEmpty();
        }

        boolean kindOnly() {
            return !byTrainNos() && !byRanges() && !byWithin() && kind != null;
        }

        boolean inRange(String catalogNo) {
            Integer n = SubwayTrainIdentity.numeric(catalogNo);
            if (n == null || excludeTrainNos.contains(catalogNo)) {
                return false;
            }
            for (int[] range : ranges) {
                if (range[0] <= n && n <= range[1]) {
                    return true;
                }
            }
            return false;
        }
    }

    sealed interface Element permits Stop, AllStops {
    }

    /** @param onlyTrains 비면 모든 열차, 아니면 그 카탈로그 번호만 정차. @param directions 비면 양방향 */
    record Stop(String name, Set<String> onlyTrains, Set<String> directions) implements Element {
    }

    record AllStops(String from, String to) implements Element {
    }

    // ── 해석 결과 ─────────────────────────────────────────────────────────

    record ResolvedStop(String stationId, Set<String> onlyTrains, Set<String> directions) implements ResolvedElement {
    }

    record ResolvedRange(String fromId, String toId) implements ResolvedElement {
    }

    sealed interface ResolvedElement permits ResolvedStop, ResolvedRange {
    }

    record Service(ExpressService info, Match match, Set<String> within, List<ResolvedElement> elements) {
    }

    /**
     * @param stationIds   노선 → (정규화 이름 → 역 id). 이름이 겹치는 역은 빠진다. 빈 노선은 넣지 않는다
     * @param pendingLines 역 목록이 비어 아직 잇지 못한 노선. 비어 있어야 완성(다시 풀지 않음)이다
     */
    record State(String version, List<Service> services, Map<String, Map<String, String>> stationIds,
                 Set<String> pendingLines) {

        boolean complete() {
            return pendingLines.isEmpty();
        }
    }

    /** 역 마스터가 빈 노선을 다시 이어 보는 간격. 그 사이 호출은 지금 상태(보류 노선 서비스 없음)로 답한다. */
    static final Duration RETRY_INTERVAL = Duration.ofSeconds(30);

    private final Function<String, List<SubwayPatternStationMatcher.Candidate>> candidates;
    private final Supplier<Catalog> source;
    private final long retryNanos;

    private volatile Catalog catalog;
    private volatile State state;
    /** 보류 노선을 다시 이어 볼 수 있는 {@link System#nanoTime()}. {@link #state} 보다 먼저 쓴다. */
    private volatile long retryAtNanos;
    private boolean pendingLogged;

    @Autowired
    public SubwayExpressCatalog(SubwayLineStationIndex stationIndex) {
        this(stationIndex::candidates, SubwayExpressCatalog::loadBundled, RETRY_INTERVAL);
    }

    private SubwayExpressCatalog(Function<String, List<SubwayPatternStationMatcher.Candidate>> candidates,
                                 Supplier<Catalog> source, Duration retryInterval) {
        this.candidates = candidates;
        this.source = source;
        this.retryNanos = retryInterval.toNanos();
    }

    /** 번들 파일을 주어진 역 목록으로 읽는다(시험용). 보류 노선은 다음 호출에 바로 다시 잇는다. */
    public static SubwayExpressCatalog of(Function<String, List<SubwayPatternStationMatcher.Candidate>> candidates) {
        return new SubwayExpressCatalog(candidates, SubwayExpressCatalog::loadBundled, Duration.ZERO);
    }

    /** 주어진 JSON 을 주어진 역 목록으로 읽는다(시험용). 보류 노선은 다음 호출에 바로 다시 잇는다. */
    public static SubwayExpressCatalog of(String json,
                                          Function<String, List<SubwayPatternStationMatcher.Candidate>> candidates) {
        return of(json, candidates, Duration.ZERO);
    }

    /** 다시 잇는 간격까지 주는 시험용. */
    static SubwayExpressCatalog of(String json, Function<String, List<SubwayPatternStationMatcher.Candidate>> candidates,
                                   Duration retryInterval) {
        return new SubwayExpressCatalog(candidates, () -> {
            try {
                return parse(OM.readTree(json));
            } catch (IOException e) {
                throw new IllegalArgumentException(e);
            }
        }, retryInterval);
    }

    // ── 조회 ─────────────────────────────────────────────────────────────

    /** 카탈로그 판(version). 파일을 못 읽었으면 null. */
    public String version() {
        return state().version();
    }

    /** 켜진(역을 다 이은) 서비스. */
    public List<ExpressService> services() {
        return state().services().stream().map(Service::info).toList();
    }

    public Optional<ExpressService> service(String serviceId) {
        return find(serviceId).map(Service::info);
    }

    /**
     * 급행 열차 하나가 어느 서비스인가. 규칙은 클래스 주석. 근거가 없거나 둘 이상이면 empty.
     *
     * @param destination      행선지 이름이나 종착 역 id
     * @param currentStationId 지금 있는(또는 막 지난) 역 id. 모르면 null
     */
    public Optional<ExpressService> resolve(String lineId, String direction, String trainNo, String destination,
                                            String currentStationId) {
        return resolve(SubwayTrainIdentity.of(lineId, direction, trainNo, destination), currentStationId);
    }

    public Optional<ExpressService> resolve(SubwayTrainIdentity train, String currentStationId) {
        if (train == null || train.lineId() == null) {
            return Optional.empty();
        }
        List<Service> onLine = state().services().stream()
                .filter(s -> train.lineId().equals(s.info().lineId()))
                .toList();
        String no = train.catalogNo();
        Optional<Service> picked = pick(onLine.stream().filter(s -> no != null && s.match().trainNos().contains(no))
                .toList());
        if (picked == null) {
            picked = pick(onLine.stream().filter(s -> s.match().byRanges() && s.match().inRange(no)).toList());
        }
        if (picked == null) {
            List<Service> areal = onLine.stream().filter(s -> s.match().byWithin()).toList();
            if (!areal.isEmpty()) {
                String where = currentStationId != null ? currentStationId : destinationId(train);
                if (where == null) {
                    return Optional.empty(); // 구간으로 가르는 노선인데 어디 있는지 모른다
                }
                picked = pick(areal.stream().filter(s -> s.within().contains(where)).toList());
            }
        }
        if (picked == null) {
            picked = pick(onLine.stream().filter(s -> s.match().kindOnly()).toList());
        }
        return picked == null ? Optional.empty() : picked.map(Service::info);
    }

    /**
     * 운영사가 급행으로 표시하지만 운행 구간 모든 역에 서는 열차인가(1호선 용산↔구로 '구로 급행' 1401–1416). 분류기가 실시간
     * '급행' 표시를 뒤집어 일반으로 보는 근거다. 이런 열차는 어느 서비스에도 속하지 않는다.
     */
    public boolean isAllStopTrain(String lineId, String trainNo) {
        String no = SubwayTrainIdentity.catalogNumber(trainNo);
        if (lineId == null || no == null) {
            return false;
        }
        return catalog().allStopTrains().stream()
                .filter(rule -> lineId.equals(rule.lineId()))
                .anyMatch(rule -> rule.match().trainNos().contains(no) || rule.match().inRange(no));
    }

    /**
     * 번호만으로 급행인 열차인가(실시간 '일반' 표시를 뒤집는 근거, 4호선 K44xx). 파일 정의를 본다 — 역 id 에 잇지 못한(보류·
     * 꺼진) 서비스의 열차도 급행이다. 급행인데 일반으로 보면 지나치는 역을 정차로 세고, 급행으로 보면 해석 실패(추적 불가)로
     * 안전하게 막힌다.
     */
    public boolean isTrainNoAuthoritativeExpress(String lineId, String trainNo) {
        String no = SubwayTrainIdentity.catalogNumber(trainNo);
        if (lineId == null || no == null) {
            return false;
        }
        return catalog().services().stream()
                .filter(d -> lineId.equals(d.lineId()) && d.match().trainNoAuthoritative())
                .anyMatch(d -> d.match().trainNos().contains(no) || d.match().inRange(no));
    }

    /** 이 노선에 급행 서비스 정의가 있는가(역 id 에 이었는지와 무관). */
    public boolean hasServices(String lineId) {
        return lineId != null && catalog().services().stream().anyMatch(d -> lineId.equals(d.lineId()));
    }

    /**
     * 서비스가 이 방향·이 열차로 서는 역. 열차별 예외는 {@code trainNo} 로 정한다 — 번호를 모르면(경로 검색) 예외 정차역은
     * 빼고 준다(서는지 모르는 역을 정차로 세지 않는다). 각역 구간을 펼치지 못하면 empty.
     */
    public Optional<SubwayStopPattern> pattern(String serviceId, String direction, String trainNo,
                                               AllStopPath allStopPath) {
        Optional<Service> found = find(serviceId);
        if (found.isEmpty() || !("상행".equals(direction) || "하행".equals(direction))) {
            return Optional.empty();
        }
        Service service = found.get();
        String lineId = service.info().lineId();
        boolean reversed = !direction.equals(service.info().listedDirection());
        List<ResolvedElement> elements = new ArrayList<>(service.elements());
        if (reversed) {
            Collections.reverse(elements);
        }
        String no = SubwayTrainIdentity.catalogNumber(trainNo);
        List<String> ids = new ArrayList<>();
        for (ResolvedElement element : elements) {
            if (element instanceof ResolvedStop stop) {
                if ((stop.onlyTrains().isEmpty() || stop.onlyTrains().contains(no))
                        && (stop.directions().isEmpty() || stop.directions().contains(direction))) {
                    appendDistinct(ids, stop.stationId());
                }
            } else if (element instanceof ResolvedRange range) {
                String from = reversed ? range.toId() : range.fromId();
                String to = reversed ? range.fromId() : range.toId();
                List<String> path = allStopPath == null ? List.of() : allStopPath.path(lineId, direction, from, to);
                if (path.isEmpty()) {
                    return Optional.empty();
                }
                path.forEach(id -> appendDistinct(ids, id));
            }
        }
        return Optional.of(new SubwayStopPattern(serviceId, lineId, direction, state().version(), ids));
    }

    /** 노선 안 역 이름(정규화·별칭 전도 된다) → 역 id. 없거나 같은 이름이 둘이면 empty. */
    public Optional<String> stationId(String lineId, String name) {
        String key = SubwayTrainIdentity.destinationName(name);
        if (lineId == null || key == null) {
            return Optional.empty();
        }
        Map<String, Map<String, String>> byLine = state().stationIds();
        Map<String, String> ids = byLine.get(lineId);
        if (ids == null) {
            ids = indexLine(lineId);
            if (!ids.isEmpty()) {
                // 빈 표는 남기지 않는다 — 역 마스터 적재 전 첫 호출이 그 노선을 영구히 못 잇게 만든다
                byLine.putIfAbsent(lineId, ids);
            }
        }
        return Optional.ofNullable(ids.get(key));
    }

    // ── 내부 ─────────────────────────────────────────────────────────────

    /** 정확히 하나면 그것, 여럿이면 empty(모호), 없으면 null(다음 단계로). */
    private static Optional<Service> pick(List<Service> matches) {
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() > 1) {
            log.debug("[Subway] 급행 서비스 모호 — 답하지 않음 services={}",
                    matches.stream().map(s -> s.info().id()).toList());
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }

    private String destinationId(SubwayTrainIdentity train) {
        if (train.terminalId() != null) {
            return train.terminalId();
        }
        return stationId(train.lineId(), train.destinationName()).orElse(null);
    }

    private Optional<Service> find(String serviceId) {
        if (serviceId == null) {
            return Optional.empty();
        }
        return state().services().stream().filter(s -> serviceId.equals(s.info().id())).findFirst();
    }

    private static void appendDistinct(List<String> ids, String id) {
        if (ids.isEmpty() || !ids.get(ids.size() - 1).equals(id)) {
            ids.add(id);
        }
    }

    /**
     * 역 id 에 이은 상태. 완성되면 volatile 읽기 하나로 끝난다. 보류 노선이 있으면 {@link #RETRY_INTERVAL} 마다 한 호출만
     * (잠금 안에서) 다시 잇고, 나머지 호출은 기다리지 않고 지금 상태로 답한다.
     */
    State state() {
        State loaded = state;
        if (loaded != null && (loaded.complete() || System.nanoTime() - retryAtNanos < 0)) {
            return loaded;
        }
        synchronized (this) {
            loaded = state;
            if (loaded == null || (!loaded.complete() && System.nanoTime() - retryAtNanos >= 0)) {
                loaded = resolveAll(catalog());
                retryAtNanos = System.nanoTime() + retryNanos;
                state = loaded;
            }
            return loaded;
        }
    }

    private Catalog catalog() {
        Catalog loaded = catalog;
        if (loaded == null) {
            try {
                loaded = source.get();
            } catch (RuntimeException e) {
                log.warn("[Subway] 급행 카탈로그 적재 실패 — 급행 해석 없이 진행: {}", e.getMessage());
                loaded = new Catalog(null, List.of(), List.of());
            }
            catalog = loaded;
        }
        return loaded;
    }

    private State resolveAll(Catalog catalog) {
        Map<String, Map<String, String>> byLine = new HashMap<>();
        List<Service> services = new ArrayList<>();
        Set<String> pending = new LinkedHashSet<>();
        for (Definition def : catalog.services()) {
            Map<String, String> ids = byLine.computeIfAbsent(def.lineId(), this::indexLine);
            if (ids.isEmpty()) {
                // 역 목록이 비었다 = 역 마스터를 아직 못 읽었다. 이름이 틀린 것과 다르다 — 끄지 않고 다음에 다시 잇는다
                pending.add(def.lineId());
                continue;
            }
            List<String> missing = new ArrayList<>();
            List<ResolvedElement> elements = new ArrayList<>();
            for (Element element : def.elements()) {
                if (element instanceof Stop stop) {
                    String id = idOf(ids, stop.name(), missing);
                    elements.add(new ResolvedStop(id, stop.onlyTrains(), stop.directions()));
                } else if (element instanceof AllStops range) {
                    elements.add(new ResolvedRange(idOf(ids, range.from(), missing), idOf(ids, range.to(), missing)));
                }
            }
            Set<String> within = new LinkedHashSet<>();
            def.match().within().forEach(name -> within.add(idOf(ids, name, missing)));
            if (!missing.isEmpty()) {
                log.warn("[Subway] 급행 서비스 끔 — 역 이름을 역 id 에 잇지 못함 service={} line={} names={}",
                        def.id(), def.lineId(), missing);
                continue;
            }
            services.add(new Service(new ExpressService(def.id(), def.lineId(), def.label(), def.listedDirection(),
                    catalog.version(), def.effectiveFrom(), def.source(), def.match().trainNoAuthoritative()),
                    def.match(), Set.copyOf(within), List.copyOf(elements)));
        }
        if (pending.isEmpty()) {
            log.info("[Subway] 급행 카탈로그 적재 version={} services={}/{}", catalog.version(), services.size(),
                    catalog.services().size());
        } else if (!pendingLogged) {
            pendingLogged = true;
            log.warn("[Subway] 급행 카탈로그 — 역 마스터가 빈 노선 보류, {}초 뒤 다시 잇는다 lines={} services={}/{}",
                    RETRY_INTERVAL.toSeconds(), pending, services.size(), catalog.services().size());
        } else {
            log.debug("[Subway] 급행 카탈로그 — 아직 보류 lines={}", pending);
        }
        Map<String, Map<String, String>> stationIds = new ConcurrentHashMap<>();
        byLine.forEach((lineId, ids) -> {
            if (!ids.isEmpty()) {
                stationIds.put(lineId, ids);
            }
        });
        return new State(catalog.version(), List.copyOf(services), stationIds, Set.copyOf(pending));
    }

    private static String idOf(Map<String, String> ids, String name, List<String> missing) {
        String id = ids.get(SubwayTrainIdentity.destinationName(name));
        if (id == null) {
            missing.add(name);
        }
        return id;
    }

    /** 정규화 이름 → 역 id. 같은 노선에 같은 정규화 이름이 둘 이상이면 그 이름은 넣지 않는다(지어내지 않는다). */
    private Map<String, String> indexLine(String lineId) {
        Map<String, String> ids = new HashMap<>();
        Set<String> duplicated = new HashSet<>();
        for (SubwayPatternStationMatcher.Candidate c : candidates.apply(lineId)) {
            String name = NORMALIZER.normalize(c.stationName());
            if (name == null) {
                continue;
            }
            String prev = ids.putIfAbsent(name, c.stationId());
            if (prev != null && !prev.equals(c.stationId())) {
                duplicated.add(name);
            }
        }
        duplicated.forEach(ids::remove);
        return Map.copyOf(ids);
    }

    // ── 파일 읽기 ─────────────────────────────────────────────────────────

    private static Catalog loadBundled() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return parse(OM.readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException(RESOURCE + " 읽기 실패: " + e.getMessage(), e);
        }
    }

    static Catalog parse(JsonNode root) {
        List<Definition> services = new ArrayList<>();
        for (JsonNode s : root.path("services")) {
            JsonNode m = s.path("match");
            Match match = new Match(text(m, "kind"), numbers(m.path("trainNos")), ranges(m.path("trainNoRanges")),
                    numbers(m.path("excludeTrainNos")), List.copyOf(strings(m.path("within"))),
                    m.path("trainNoAuthoritative").asBoolean(false));
            List<Element> elements = new ArrayList<>();
            for (JsonNode e : s.path("stops")) {
                if (e.has("allStops")) {
                    elements.add(new AllStops(e.path("allStops").path("from").asText(),
                            e.path("allStops").path("to").asText()));
                } else {
                    elements.add(new Stop(e.path("name").asText(), numbers(e.path("onlyTrains")),
                            strings(e.path("directions"))));
                }
            }
            String effective = text(s, "effectiveFrom");
            services.add(new Definition(s.path("id").asText(), s.path("lineId").asText(), text(s, "label"),
                    s.path("listedDirection").asText("하행"), match, List.copyOf(elements),
                    List.copyOf(strings(s.path("source"))), effective == null ? null : LocalDate.parse(effective)));
        }
        List<AllStopRule> allStop = new ArrayList<>();
        for (JsonNode a : root.path("allStopTrains")) {
            allStop.add(new AllStopRule(a.path("lineId").asText(), new Match(null, numbers(a.path("trainNos")),
                    ranges(a.path("trainNoRanges")), numbers(a.path("excludeTrainNos")), List.of(), false)));
        }
        return new Catalog(text(root, "version"), List.copyOf(services), List.copyOf(allStop));
    }

    private static List<int[]> ranges(JsonNode array) {
        List<int[]> ranges = new ArrayList<>();
        for (JsonNode r : array) {
            ranges.add(new int[]{Integer.parseInt(SubwayTrainIdentity.catalogNumber(r.get(0).asText())),
                    Integer.parseInt(SubwayTrainIdentity.catalogNumber(r.get(1).asText()))});
        }
        return List.copyOf(ranges);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode v : array) {
            out.add(v.asText());
        }
        return Collections.unmodifiableSet(out);
    }

    /** 열차번호는 카탈로그 비교용 번호로 맞춰 둔다(파일에 K 나 선행 0 이 섞여도 같은 값). */
    private static Set<String> numbers(JsonNode array) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode v : array) {
            out.add(SubwayTrainIdentity.catalogNumber(v.asText()));
        }
        return Collections.unmodifiableSet(out);
    }
}
