package watoo.grd.nextroute.application.subway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.subway.service.EventTimetablePairer.MatchedPair;
import watoo.grd.nextroute.domain.subway.entity.MlSubwayDelayTruth;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEvent;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.entity.SubwayTimetable;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ML 학습 정답 라벨 생성 서비스 — subway_arrival_event(실측) × subway_timetable
 * (예정) 의 성공 매칭 pair로부터 delay_seconds 를 산출해 ml_subway_delay_truth
 * 에 누적한다.
 *
 * <p>두 가지 매칭 버전을 지원한다 ({@code batch.delay-truth.matching-version}).
 *
 * <ul>
 *   <li><b>v1</b> (legacy): {@link EventTimetablePairer} 강제 ordinal 매칭.
 *       OUTLIER_DELAY 기준 학습 제외 처리.</li>
 *   <li><b>v2</b> (default): {@link EventTimetablePairerV2}로 count guard +
 *       time-window guard + destination hard reject 통과한 pair만 truth로 저장.
 *       rejected 그룹은 issue table로 분리되어 truth row를 만들지 않는다.</li>
 * </ul>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class SubwayDelayTruthGenerationService {

    private final SubwayDataService subwayDataService;
    private final TimetableConverter converter;
    private final EventTimetablePairer pairer;
    private final EventTimetablePairerV2 pairerV2;

    public SubwayDelayTruthGenerationService(SubwayDataService subwayDataService,
                                             TimetableConverter converter,
                                             EventTimetablePairer pairer,
                                             EventTimetablePairerV2 pairerV2) {
        this.subwayDataService = subwayDataService;
        this.converter = converter;
        this.pairer = pairer;
        this.pairerV2 = pairerV2;
    }

    /** V1 |delay| 임계값 — 초과 시 학습 제외(분포 분석 위해 row는 보존) */
    static final int OUTLIER_THRESHOLD_SECONDS = 900;

    static final String MATCH_STRATEGY_ORDINAL = "ORDINAL";
    static final String EXCLUDE_REASON_OUTLIER = "OUTLIER_DELAY";
    static final String EXCLUDE_REASON_INFERRED = "INFERRED_EVENT";

    @Value("${batch.delay-truth.matching-version:v2}")
    String matchingVersion;

    @Value("${batch.delay-truth.max-match-distance-seconds:1800}")
    long maxMatchDistanceSeconds;

    @Value("${batch.persistence.truth-chunk-size:2000}")
    int truthChunkSize;

    @Transactional
    public int generateForDate(LocalDate serviceDate) {
        if ("v2".equalsIgnoreCase(matchingVersion)) {
            return generateForDateV2(serviceDate);
        }
        return generateForDateV1(serviceDate);
    }

    // ── V1 (legacy) ─────────────────────────────────────────────────────────

    private int generateForDateV1(LocalDate serviceDate) {
        subwayDataService.deleteDelayTruthByServiceDate(serviceDate);

        List<SubwayArrivalEvent> events = subwayDataService.findArrivalEventsByServiceDate(serviceDate);
        String dayType = converter.toDayType(serviceDate);
        List<SubwayStation> stations = subwayDataService.findMappableStations();

        Set<String> lineIds = new HashSet<>();
        for (SubwayStation st : stations) lineIds.add(st.getLineId());
        for (SubwayArrivalEvent ev : events) lineIds.add(ev.getLineId());
        List<SubwayTimetable> timetables =
                subwayDataService.findTimetablesByDayTypeAndLineIdIn(dayType, lineIds);

        List<SubwayArrivalEvent> filteredEvents = events.stream()
                .filter(ev -> ev.getArrivedAt() != null)
                .toList();
        List<SubwayTimetable> filteredTimetables = timetables.stream()
                .filter(tt -> converter.toScheduledArrivalAt(
                        serviceDate, tt.getArrTime(), tt.getDepTime()) != null)
                .toList();

        EventTimetablePairer.PairingResult result =
                pairer.pair(serviceDate, dayType, filteredEvents, stations, filteredTimetables);

        List<MlSubwayDelayTruth> truths = new ArrayList<>(result.matched().size());
        int inferredExcluded = 0;
        int outlierExcluded = 0;

        for (MatchedPair p : result.matched()) {
            SubwayArrivalEvent ev = p.event().event();
            SubwayTimetable tt = p.timetable().timetable();
            LocalDateTime scheduled = p.timetable().scheduledArrivalAt();
            LocalDateTime actual = ev.getArrivedAt();
            int delay = (int) Duration.between(scheduled, actual).getSeconds();

            String evSource = ev.getEventSource();
            boolean inferred = SubwayInferredArrivalCompletionService.EVENT_SOURCE.equals(evSource);
            boolean outlier = Math.abs(delay) > OUTLIER_THRESHOLD_SECONDS;

            String excludeReason = null;
            if (inferred) { excludeReason = EXCLUDE_REASON_INFERRED; inferredExcluded++; }
            else if (outlier) { excludeReason = EXCLUDE_REASON_OUTLIER; outlierExcluded++; }
            boolean excluded = excludeReason != null;

            int evCnt = p.eventCount();
            int ttCnt = p.timetableCount();
            int denom = Math.max(Math.max(evCnt, ttCnt), 1);
            double groupCompleteness = 1.0 - Math.min(1.0, Math.abs(evCnt - ttCnt) / (double) denom);
            double delayDecay = Math.max(0.0, 1.0 - Math.abs(delay) / (double) OUTLIER_THRESHOLD_SECONDS);
            double confidence = groupCompleteness * delayDecay;

            truths.add(buildTruth(p.key().lineId(), p.key().stationId(), p.station(),
                    p.key().directionUD(), dayType, ev, tt, scheduled, actual, delay,
                    evSource, p.timetable().scheduledTimeSource(),
                    p.timetableOrderIndex(), p.eventOrderIndex(), p.matchGroupKey(),
                    serviceDate, MATCH_STRATEGY_ORDINAL, confidence, excluded, excludeReason));
        }

        subwayDataService.saveAllDelayTruth(truths);

        int trainable = truths.size() - inferredExcluded - outlierExcluded;
        log.info("[DelayTruth v1] serviceDate={} matched={} trainable={} excluded(inferred={}, outlier={}) eventTotal={}",
                serviceDate, truths.size(), trainable, inferredExcluded, outlierExcluded, events.size());

        return truths.size();
    }

    // ── V2 ────────────────────────────────────────────────────────────────

    private int generateForDateV2(LocalDate serviceDate) {
        subwayDataService.deleteDelayTruthByServiceDate(serviceDate);

        List<SubwayArrivalEvent> events = subwayDataService.findArrivalEventsByServiceDate(serviceDate);
        String dayType = converter.toDayType(serviceDate);
        List<SubwayStation> stations = subwayDataService.findMappableStations();

        Set<String> lineIds = new HashSet<>();
        for (SubwayStation st : stations) lineIds.add(st.getLineId());
        for (SubwayArrivalEvent ev : events) lineIds.add(ev.getLineId());
        List<SubwayTimetable> timetables =
                subwayDataService.findTimetablesByDayTypeAndLineIdIn(dayType, lineIds);

        List<SubwayArrivalEvent> filteredEvents = events.stream()
                .filter(ev -> ev.getArrivedAt() != null)
                .toList();
        List<SubwayTimetable> filteredTimetables = timetables.stream()
                .filter(tt -> converter.toScheduledArrivalAt(
                        serviceDate, tt.getArrTime(), tt.getDepTime()) != null)
                .toList();

        EventTimetablePairerV2.PairingResult result = pairerV2.pair(
                serviceDate, dayType, filteredEvents, stations, filteredTimetables,
                maxMatchDistanceSeconds);

        // ── chunked save state ────────────────────────────────────────────
        List<MlSubwayDelayTruth> chunk = new ArrayList<>(truthChunkSize);
        int totalSaved = 0;
        int savedChunks = 0;
        int inferredExcluded = 0;

        for (MatchedPair p : result.matched()) {
            SubwayArrivalEvent ev = p.event().event();
            SubwayTimetable tt = p.timetable().timetable();
            LocalDateTime scheduled = p.timetable().scheduledArrivalAt();
            LocalDateTime actual = ev.getArrivedAt();
            int delay = (int) Duration.between(scheduled, actual).getSeconds();

            String evSource = ev.getEventSource();
            boolean inferred = SubwayInferredArrivalCompletionService.EVENT_SOURCE.equals(evSource);
            String excludeReason = inferred ? EXCLUDE_REASON_INFERRED : null;
            if (inferred) inferredExcluded++;

            // V2는 time-window 통과 + count equal pair만 도달 → groupCompleteness=1
            double delayDecay = Math.max(0.0,
                    1.0 - Math.abs(delay) / (double) maxMatchDistanceSeconds);
            double confidence = delayDecay;

            chunk.add(buildTruth(p.key().lineId(), p.key().stationId(), p.station(),
                    p.key().directionUD(), dayType, ev, tt, scheduled, actual, delay,
                    evSource, p.timetable().scheduledTimeSource(),
                    p.timetableOrderIndex(), p.eventOrderIndex(), p.matchGroupKey(),
                    serviceDate, MATCH_STRATEGY_ORDINAL, confidence,
                    excludeReason != null, excludeReason));

            if (chunk.size() >= truthChunkSize) {
                int size = chunk.size();
                subwayDataService.saveAllDelayTruth(chunk);
                subwayDataService.flushAndClear();
                totalSaved += size;
                savedChunks++;
                chunk = new ArrayList<>(truthChunkSize);
            }
        }
        if (!chunk.isEmpty()) {
            int size = chunk.size();
            subwayDataService.saveAllDelayTruth(chunk);
            subwayDataService.flushAndClear();
            totalSaved += size;
            savedChunks++;
        }

        int trainable = totalSaved - inferredExcluded;
        log.info("[DelayTruth v2] serviceDate={} matched={} trainable={} inferredExcluded={} "
                        + "rejectedTimeDistance_groups={} destinationMismatch_groups={} countMismatch_groups={} "
                        + "eventTotal={} savedChunks={}",
                serviceDate, totalSaved, trainable, inferredExcluded,
                result.rejectedByTimeDistance().size(),
                result.destinationMismatch().size(),
                result.countMismatch().size(),
                events.size(), savedChunks);

        return totalSaved;
    }

    private MlSubwayDelayTruth buildTruth(String lineId, String stationId, SubwayStation station,
                                          String direction, String dayType,
                                          SubwayArrivalEvent ev, SubwayTimetable tt,
                                          LocalDateTime scheduled, LocalDateTime actual, int delay,
                                          String evSource, String scheduledTimeSource,
                                          Integer ttOrder, Integer evOrder, String matchGroupKey,
                                          LocalDate serviceDate, String matchStrategy,
                                          double confidence, boolean excluded, String excludeReason) {
        return MlSubwayDelayTruth.builder()
                .serviceDate(serviceDate)
                .lineId(lineId)
                .stationId(stationId)
                .stationName(station.getStationName())
                .tagoStationId(station.getTagoStationId())
                .direction(direction)
                .dayType(dayType)
                .trainNo(ev.getTrainNo())
                .trainType(ev.getTrainType())
                .destinationId(ev.getDestinationId())
                .destinationName(ev.getDestinationName())
                .endStationName(tt.getEndStationName())
                .arrivalEventId(ev.getId())
                .timetableId(tt.getId())
                .scheduledArrivalAt(scheduled)
                .actualArrivedAt(actual)
                .delaySeconds(delay)
                .eventSource(evSource)
                .scheduledTimeSource(scheduledTimeSource)
                .timetableOrderIndex(ttOrder)
                .eventOrderIndex(evOrder)
                .matchGroupKey(matchGroupKey)
                .matchStrategy(matchStrategy)
                .matchConfidence(confidence)
                .excludedFromTraining(excluded)
                .excludeReason(excludeReason)
                .build();
    }
}
