package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalRaw;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

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

        if (!raws.isEmpty()) {
            int inserted = subwayDataService.insertArrivalRawIgnoreDuplicates(raws);
            //log.info("[SubwayArrivalRawRecorder] Inserted {}/{} arrival rows", inserted, raws.size());
        }
    }

    private boolean hasRequiredFields(SubwayArrivalInfo a) {
        return hasText(a.lineId())
                && hasText(a.stationId())
                && hasText(a.trainNo())
                && hasText(a.receivedAt())
                && hasText(a.arrivalCode())
                && hasText(a.currentMessage());
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
