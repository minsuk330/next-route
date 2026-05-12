package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalRaw;
import watoo.grd.nextroute.domain.subway.service.ArrivalRawInsertResult;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubwayArrivalRawRecorder {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final SubwayDataService subwayDataService;

    public void record(List<SubwayArrivalInfo> arrivals) {
        List<SubwayArrivalRaw> raws = arrivals.stream()
                .filter(this::hasRequiredFields)
                .map(this::toRaw)
                .toList();

        logDiagnostics(arrivals, raws);

        if (!raws.isEmpty()) {
            ArrivalRawInsertResult result = subwayDataService.insertArrivalRawIgnoreDuplicates(raws);
            log.info(
                    "[SubwayArrivalRawRecorder] Raw 저장 결과: 전체시도={}건, 전체저장={}건, 전체중복제외={}건, " +
                            "도착코드1_시도={}건, 도착코드1_저장={}건, 도착코드1_중복제외={}건",
                    result.attemptedRows(),
                    result.insertedRows(),
                    result.duplicateRows(),
                    result.attemptedCode1Rows(),
                    result.insertedCode1Rows(),
                    result.duplicateCode1Rows()
            );
        }
    }

    private void logDiagnostics(List<SubwayArrivalInfo> arrivals, List<SubwayArrivalRaw> raws) {
        long responseCode1Rows = arrivals.stream()
                .filter(a -> "1".equals(a.arrivalCode()))
                .count();
        long persistableCode1Rows = raws.stream()
                .filter(r -> "1".equals(r.getArrivalCode()))
                .count();

        long code1MissingLineId = arrivals.stream()
                .filter(a -> "1".equals(a.arrivalCode()))
                .filter(a -> !hasText(a.lineId()))
                .count();
        long code1MissingStationId = arrivals.stream()
                .filter(a -> "1".equals(a.arrivalCode()))
                .filter(a -> !hasText(a.stationId()))
                .count();
        long code1MissingDirection = arrivals.stream()
                .filter(a -> "1".equals(a.arrivalCode()))
                .filter(a -> !hasText(a.direction()))
                .count();
        long code1MissingTrainNo = arrivals.stream()
                .filter(a -> "1".equals(a.arrivalCode()))
                .filter(a -> !hasText(a.trainNo()))
                .count();
        long code1MissingReceivedAt = arrivals.stream()
                .filter(a -> "1".equals(a.arrivalCode()))
                .filter(a -> !hasText(a.receivedAt()))
                .count();
        long code1MissingCurrentMessage = arrivals.stream()
                .filter(a -> "1".equals(a.arrivalCode()))
                .filter(a -> !hasText(a.currentMessage()))
                .count();

        log.info(
                "[SubwayArrivalRawRecorder] Raw 진단: API응답전체={}건, 저장대상={}건, 필수값누락제외={}건, " +
                        "API응답중_도착코드1={}건, 저장대상중_도착코드1={}건, 도착코드별분포={}, " +
                        "도착코드1_호선ID누락={}건, 도착코드1_역ID누락={}건, 도착코드1_방향누락={}건, " +
                        "도착코드1_열차번호누락={}건, 도착코드1_수신시각누락={}건, 도착코드1_현재메시지누락={}건",
                arrivals.size(),
                raws.size(),
                arrivals.size() - raws.size(),
                responseCode1Rows,
                persistableCode1Rows,
                arrivalCodeCounts(arrivals),
                code1MissingLineId,
                code1MissingStationId,
                code1MissingDirection,
                code1MissingTrainNo,
                code1MissingReceivedAt,
                code1MissingCurrentMessage
        );
    }

    private Map<String, Long> arrivalCodeCounts(List<SubwayArrivalInfo> arrivals) {
        return arrivals.stream()
                .collect(Collectors.groupingBy(
                        a -> hasText(a.arrivalCode()) ? a.arrivalCode() : "NULL_OR_BLANK",
                        TreeMap::new,
                        Collectors.counting()
                ));
    }

    private boolean hasRequiredFields(SubwayArrivalInfo a) {
        return hasText(a.lineId())
                && hasText(a.stationId())
                && hasText(a.trainNo())
                && hasText(a.receivedAt())
                && hasText(a.currentMessage())
                && hasText(a.direction())
                && hasText(a.arrivalCode());

    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private SubwayArrivalRaw toRaw(SubwayArrivalInfo a) {
        return SubwayArrivalRaw.builder()
                .collectedAt(LocalDateTime.now(KST))
                .stationId(a.stationId())
                .stationName(a.stationName())
                .lineId(a.lineId())
                .direction(a.direction())
                .prevStationId(a.prevStationId())
                .nextStationId(a.nextStationId())
                .transferCount(a.transferCount())
                .ordkey(a.ordkey())
                .transferLines(a.transferLines())
                .transferStations(a.transferStations())
                .trainType(a.trainType())
                .arrivalSeconds(a.arrivalSeconds())
                .trainNo(a.trainNo())
                .destinationId(a.destinationId())
                .destinationName(a.destinationName())
                .currentMessage(a.currentMessage())
                .arrivalCode(a.arrivalCode())
                .subwayId(a.subwayId())
                .arrivalMsg3(a.arrivalMsg3())
                .receivedAt(a.receivedAt())
                .trainLineName(a.trainLineName())
                .lastTrainYn(a.lastTrainYn())
                .build();
    }
}
