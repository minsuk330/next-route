package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
 * <p>역할: <b>성공 라벨 저장</b>. (실패 진단은 {@link TimetableMatchingService})
 * 페어링은 공유 {@link EventTimetablePairer} 사용, 사전 필터링은 호출 단계에서
 * 입력에 적용해 짝 정합을 보장한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubwayDelayTruthGenerationService {

    private final SubwayDataService subwayDataService;
    private final TimetableConverter converter;
    private final EventTimetablePairer pairer;

    /** |delay| 임계값 — 초과 시 학습 제외(분포 분석 위해 row는 보존) */
    static final int OUTLIER_THRESHOLD_SECONDS = 900;

    static final String MATCH_STRATEGY_ORDINAL = "ORDINAL";
    static final String EXCLUDE_REASON_OUTLIER = "OUTLIER_DELAY";
    static final String EXCLUDE_REASON_INFERRED = "INFERRED_EVENT";

    @Transactional
    public int generateForDate(LocalDate serviceDate) {
        // 1) 멱등 — 기존 truth 제거
        subwayDataService.deleteDelayTruthByServiceDate(serviceDate);

        // 2~4) 입력 로드
        List<SubwayArrivalEvent> events = subwayDataService.findArrivalEventsByServiceDate(serviceDate);
        String dayType = converter.toDayType(serviceDate);
        List<SubwayStation> stations = subwayDataService.findMappableStations();

        Set<String> lineIds = new HashSet<>();
        for (SubwayStation st : stations) lineIds.add(st.getLineId());
        for (SubwayArrivalEvent ev : events) lineIds.add(ev.getLineId());
        List<SubwayTimetable> timetables =
                subwayDataService.findTimetablesByDayTypeAndLineIdIn(dayType, lineIds);

        // 5) 사전 필터 — **정렬·페어링 *전*** (C1: 짝 어긋남 방지)
        //    - 페어링 결과에 영향 주는 필터링은 입력 단계에서 수행
        //    - direction 변환 실패/매핑 없음은 pairer 내부에서 matched에 안 들어가므로 자동 제외
        List<SubwayArrivalEvent> filteredEvents = events.stream()
                .filter(ev -> ev.getArrivedAt() != null)
                .toList();
        List<SubwayTimetable> filteredTimetables = timetables.stream()
                .filter(tt -> converter.toScheduledArrivalAt(
                        serviceDate, tt.getArrTime(), tt.getDepTime()) != null)
                .toList();

        // 6) 공유 페어링 (orderKey ASC + min-pair)
        EventTimetablePairer.PairingResult result =
                pairer.pair(serviceDate, dayType, filteredEvents, stations, filteredTimetables);

        // 7~10) matched 페어 → truth row
        List<MlSubwayDelayTruth> truths = new ArrayList<>(result.matched().size());
        int inferredExcluded = 0;
        int outlierExcluded = 0;

        for (EventTimetablePairer.MatchedPair p : result.matched()) {
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

            // pair-level match_confidence = groupCompleteness × delayDecay
            int evCnt = p.eventCount();
            int ttCnt = p.timetableCount();
            int denom = Math.max(Math.max(evCnt, ttCnt), 1);
            double groupCompleteness = 1.0 - Math.min(1.0, Math.abs(evCnt - ttCnt) / (double) denom);
            double delayDecay = Math.max(0.0, 1.0 - Math.abs(delay) / (double) OUTLIER_THRESHOLD_SECONDS);
            double confidence = groupCompleteness * delayDecay;

            truths.add(MlSubwayDelayTruth.builder()
                    .serviceDate(serviceDate)
                    .lineId(p.key().lineId())
                    .stationId(p.key().stationId())
                    .stationName(p.station().getStationName())
                    .tagoStationId(p.station().getTagoStationId())
                    .direction(p.key().directionUD())
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
                    .scheduledTimeSource(p.timetable().scheduledTimeSource())
                    .timetableOrderIndex(p.timetableOrderIndex())
                    .eventOrderIndex(p.eventOrderIndex())
                    .matchGroupKey(p.matchGroupKey())
                    .matchStrategy(MATCH_STRATEGY_ORDINAL)
                    .matchConfidence(confidence)
                    .excludedFromTraining(excluded)
                    .excludeReason(excludeReason)
                    .build());
        }

        // 11) bulk insert
        subwayDataService.saveAllDelayTruth(truths);

        int trainable = truths.size() - inferredExcluded - outlierExcluded;
        log.info("[DelayTruth] serviceDate={} matched={} trainable={} excluded(inferred={}, outlier={}) eventTotal={}",
                serviceDate, truths.size(), trainable, inferredExcluded, outlierExcluded, events.size());

        return truths.size();
    }
}
