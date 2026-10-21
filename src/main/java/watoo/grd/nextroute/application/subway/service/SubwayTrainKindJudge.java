package watoo.grd.nextroute.application.subway.service;

import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.config.SubwayExpressTrackingProperties;
import watoo.grd.nextroute.application.subway.dto.TrainKind;
import watoo.grd.nextroute.application.subway.service.SubwayStopPatternResolver.ResolvedPattern;
import watoo.grd.nextroute.application.subway.service.SubwayTrainKindClassifier.Sighting;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 실시간 열차 하나의 종류·정차 여부·추적 가능 여부(subway-express PR-S3). 열차 고르기·역 도착판·하차역 고르기가 같은 답을
 * 쓰도록 한 곳에 둔다.
 *
 * <pre>
 * 종류     {@link SubwayTrainKindClassifier#classifyTrain} — 행이 아니라 열차 단위(같은 노선·원문 번호의 근거를 모두)
 * 정차     급행만 {@link SubwayStopPatternResolver#resolve} 로 — 그 열차의 서비스·정차역, 종착역까지 자른 범위
 * 추적     NORMAL                                                              → 예
 *          EXPRESS 이고 해석 성공 · 탑승·하차역 정차(종착 범위 안) · 플래그 켜짐    → 예
 *          EXPRESS 이고 탑승역이나 하차역에 서지 않음(종착 이후 포함)             → 아니오, SKIPS_STATION
 *          그 밖 EXPRESS(해석 실패·하차역 모름·플래그 꺼짐), ITX, DIRECT           → 아니오, NOT_TRACKABLE
 *          UNKNOWN: 일반 열차만 다니는 노선이면 예, 아니면 NOT_TRACKABLE
 * </pre>
 *
 * <p>UNKNOWN 은 근거가 없다는 뜻이다(원천이 {@code trainType} 을 비워 보냄 등). 급행·ITX 가 다니는 노선에서 일반으로 추적하면
 * 지나치는 역을 다음 역으로 센다. 그런 열차가 없는 노선(2·3·5호선 등)에서는 일반과 다를 수 없어 예전처럼 고르게 둔다.
 *
 * <p>정차 칸({@code stopsAtBoarding}·{@code stopsAtAlighting})은 <b>해석된 급행만 참/거짓</b>이다. 일반 열차는 탑승역 정차가
 * 참이고 하차역은 null(일반 열차의 종착 범위는 이번에 보지 않는다 — 예전 동작 그대로). 그 밖은 null(모름).
 */
@Component
public class SubwayTrainKindJudge {

    private final SubwayTrainKindClassifier classifier;
    private final SubwayStopPatternResolver resolver;
    private final SubwayExpressTrackingProperties properties;

    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    public SubwayTrainKindJudge(SubwayTrainKindClassifier classifier, SubwayStopPatternResolver resolver,
                                SubwayExpressTrackingProperties properties) {
        this(classifier, resolver, properties, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SubwayTrainKindJudge(SubwayTrainKindClassifier classifier, SubwayStopPatternResolver resolver,
                                SubwayExpressTrackingProperties properties,
                                io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.classifier = classifier;
        this.resolver = resolver;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /** 급행인데 서비스·정차역을 못 정했다(subway-express PR-S4). 추적 불가로 막는다 — 지표로 빈도를 본다(실측 예: 1615 인천행 급행). */
    static final String UNRESOLVED_METRIC = "subway.express.unresolved";

    /**
     * 판정할 열차.
     *
     * @param direction   도착 API 표기(상행·하행·내선·외선)
     * @param trainNo     실시간 원문 번호
     * @param destination 행선지 이름이나 종착 역 id(위치 기반 출발 기록은 id 만 있다). 모르면 null
     * @param sightings   이 열차를 본 근거 전부(도착 행들·위치·출발 기록)
     */
    public record Train(String lineId, String direction, String trainNo, String destination,
                        Collection<Sighting> sightings) {

        public Train {
            sightings = sightings == null ? List.of() : List.copyOf(sightings.stream()
                    .filter(java.util.Objects::nonNull).toList());
        }
    }

    public enum Verdict {
        TRACKABLE,
        /** 422 {@code TRAIN_NOT_TRACKABLE}. */
        NOT_TRACKABLE,
        /** 422 {@code TRAIN_SKIPS_STATION}. */
        SKIPS_STATION
    }

    /**
     * @param serviceId        해석된 급행 서비스 id. 아니면 null
     * @param stopsAtBoarding  탑승역(조회역)에 서는가. 모르면 null
     * @param stopsAtAlighting 하차역에 서는가(종착 이후면 false). 묻지 않았거나 모르면 null
     * @param pattern          해석된 급행 정차 패턴. 아니면 null
     */
    public record Judgement(TrainKind kind, String serviceId, Boolean stopsAtBoarding, Boolean stopsAtAlighting,
                            Verdict verdict, ResolvedPattern pattern) {

        /** 표시 이름(일반·급행·직통·ITX-청춘). UNKNOWN 은 null. */
        public String label() {
            return kind.label();
        }

        public boolean trackable() {
            return verdict == Verdict.TRACKABLE;
        }

        /** 해석된 급행이면 그 역에 서는지(종착 이후 false), 아니면 null. */
        public Boolean stopsAt(String stationId) {
            if (pattern == null || stationId == null) {
                return null;
            }
            return pattern.stopsAt(stationId);
        }
    }

    /** 종류만. */
    public TrainKind kind(Train train) {
        return classifier.classifyTrain(train.lineId(), train.trainNo(), train.sightings());
    }

    /**
     * @param boardStationId  탑승역(역 도착판·하차역 고르기면 조회역). 급행 서비스를 구간으로 가르는 노선(수인분당)의 위치로도
     *                        쓴다 — 우리가 묻는 것은 그 역 근처의 정차다
     * @param alightStationId 하차역 id. 급행으로 해석됐을 때만 부른다(계획 체인을 읽는 비용). null 이면 묻지 않음
     */
    public Judgement judge(Train train, String boardStationId, Supplier<String> alightStationId) {
        TrainKind kind = kind(train);
        return switch (kind) {
            case NORMAL -> new Judgement(kind, null, Boolean.TRUE, null, Verdict.TRACKABLE, null);
            case UNKNOWN -> classifier.onlyNormalTrains(train.lineId())
                    ? new Judgement(kind, null, Boolean.TRUE, null, Verdict.TRACKABLE, null)
                    : new Judgement(kind, null, null, null, Verdict.NOT_TRACKABLE, null);
            case DIRECT, ITX -> new Judgement(kind, null, null, null, Verdict.NOT_TRACKABLE, null);
            case EXPRESS -> express(train, boardStationId, alightStationId);
        };
    }

    private Judgement express(Train train, String boardStationId, Supplier<String> alightStationId) {
        Optional<ResolvedPattern> resolved = resolver.resolve(train.lineId(), train.direction(), train.trainNo(),
                train.destination(), boardStationId);
        if (resolved.isEmpty()) {
            meterRegistry.counter(UNRESOLVED_METRIC, "line", train.lineId() == null ? "unknown" : train.lineId(),
                    "stage", "judge").increment();
            return new Judgement(TrainKind.EXPRESS, null, null, null, Verdict.NOT_TRACKABLE, null);
        }
        ResolvedPattern pattern = resolved.get();
        Boolean board = boardStationId == null ? null : pattern.stopsAt(boardStationId);
        String alightId = alightStationId == null ? null : alightStationId.get();
        Boolean alight = alightId == null ? null : pattern.stopsAt(alightId);
        Verdict verdict;
        if (Boolean.FALSE.equals(board) || Boolean.FALSE.equals(alight)) {
            // 열차의 성질이라 플래그보다 먼저 말한다 — 앱은 "강남역 안 서요"로 보여 준다
            verdict = Verdict.SKIPS_STATION;
        } else if (board == null || alight == null || !properties.isEnabled()) {
            verdict = Verdict.NOT_TRACKABLE;
        } else {
            verdict = Verdict.TRACKABLE;
        }
        return new Judgement(TrainKind.EXPRESS, pattern.serviceId(), board, alight, verdict, pattern);
    }

}
