package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.port.out.SubwayArrivalDepartureLogPort;
import watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "이 역을 이 시간대에 떠난 열차"의 <b>유일한</b> 조회 창구(#317-4).
 *
 * <p>역 도착 응답의 떠난 열차(#299)와 열차 선택(#293·#304)이 둘 다 여기만 본다 — 화면에 보인 떠난 열차는 반드시 고를 수
 * 있어야 한다(사용자 결정 2026-10-09). 두 곳이 원천을 따로 읽으면, 도착 API 로만 알아낸 열차를 고르는 순간 422 가 난다.
 *
 * <p>원천은 둘이다.
 * <ul>
 *   <li>{@link Source#POSITION} — 위치 API 출발 사건({@link SubwayTrainLogPort#findDepartures}). M7 판정과 같은 원천</li>
 *   <li>{@link Source#ARRIVAL} — 위치 API 가 없는 노선의 도착 API 기록({@link SubwayArrivalDepartureLogPort})</li>
 * </ul>
 * 같은 열차가 양쪽에 있으면 POSITION 이다. 운행일 경계(04:00)를 걸치면 두 운행일을 다 보고, 같은 열차는 최근 출발 하나.
 *
 * <p>한쪽 원천이 실패하면 다른 쪽만으로 답한다. M7 자동 판정은 이 창구를 쓰지 않는다 — 품질이 다른 후보를 섞지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubwayDepartureLookup {

    private final SubwayTrainLogPort trainLogPort;
    private final SubwayArrivalDepartureLogPort arrivalDepartureLogPort;
    private final ServiceDateResolver serviceDateResolver;

    public enum Source { POSITION, ARRIVAL }

    /**
     * @param observedFrom 출발이 일어났을 수 있는 가장 이른 시각. 그 뒤 위치 관측만 "떠난 뒤 위치"로 쓴다
     * @param destinationName 도착 API 원천일 때만. 위치 원천은 {@code terminalId} 로 이름을 찾는다
     * @param directAtRaw 위치 원천의 {@code directAt} 원문(subway-express PR-S3). 9 = ITX-청춘. 도착 원천·옛 기록은 null
     */
    public record Departure(String trainNo, LocalDate serviceDate, Instant departedAt, Instant observedFrom,
                            Source source, String terminalId, SubwayExpressType express,
                            String destinationName, String trainType, String directAtRaw) {

        /**
         * 도착 응답의 {@code trainType} 표기(일반·급행·특급·ITX). 도착 원천은 원문, 위치 원천은 {@code directAt} 를 옮긴다.
         * 모르면 null.
         */
        public String trainTypeLabel() {
            return source == Source.ARRIVAL ? trainType : trainTypeLabel(express, directAtRaw);
        }

        /** 위치 API {@code directAt} → 도착 응답 {@code trainType} 표기. 9 는 enum 이 UNKNOWN 으로 접으므로 원문을 본다. */
        public static String trainTypeLabel(SubwayExpressType express, String directAtRaw) {
            if (directAtRaw != null && "9".equals(directAtRaw.trim())) {
                return "ITX";
            }
            if (express == null) {
                return null;
            }
            return switch (express) {
                case NORMAL -> "일반";
                case EXPRESS -> "급행";
                case SPECIAL -> "특급";
                case UNKNOWN -> null;
            };
        }

        /** 열차 종류 분류기에 넘길 근거(subway-express PR-S3). */
        public SubwayTrainKindClassifier.Sighting sighting() {
            return source == Source.ARRIVAL
                    ? SubwayTrainKindClassifier.Sighting.ofTrainType(trainType)
                    : SubwayTrainKindClassifier.Sighting.ofDirectAt(express, directAtRaw);
        }

        /** 행선지 이름, 모르면 종착 역 id(위치 원천). 급행 정차 해석기가 둘 다 받는다. */
        public String destinationOrTerminal() {
            return destinationName != null ? destinationName : terminalId;
        }
    }

    /** 최근 출발 순. 같은 열차는 한 번. */
    public List<Departure> find(String lineId, String direction, String stationId, Instant from, Instant to) {
        Set<LocalDate> dates = new LinkedHashSet<>(List.of(serviceDateResolver.resolve(to),
                serviceDateResolver.resolve(from)));
        Map<String, Departure> byTrain = new LinkedHashMap<>();
        for (LocalDate date : dates) {
            try {
                for (SubwayTrainLogPort.TrainDeparture row
                        : trainLogPort.findDepartures(date, lineId, direction, stationId, from, to)) {
                    keepLatest(byTrain, new Departure(row.trainNo(), date, row.event().estimate(), row.event().from(),
                            Source.POSITION, row.event().terminalId(), row.event().express(), null, null,
                            row.event().directAtRaw()));
                }
            } catch (RuntimeException e) {
                log.warn("떠난 열차 위치 원천 조회 실패 line={} station={}: {}", lineId, stationId, e.getMessage());
            }
        }
        for (LocalDate date : dates) {
            try {
                for (SubwayArrivalDepartureLogPort.Row row
                        : arrivalDepartureLogPort.find(date, lineId, direction, stationId, from, to)) {
                    Departure existing = byTrain.get(row.trainNo());
                    if (existing != null && existing.source() == Source.POSITION) {
                        continue;
                    }
                    keepLatest(byTrain, new Departure(row.trainNo(), date, row.departedAt(), row.departedAt(),
                            Source.ARRIVAL, null, null, row.destinationName(), row.trainType(), null));
                }
            } catch (RuntimeException e) {
                log.warn("떠난 열차 도착 원천 조회 실패 line={} station={}: {}", lineId, stationId, e.getMessage());
            }
        }
        return byTrain.values().stream()
                .sorted(Comparator.comparing(Departure::departedAt).reversed())
                .toList();
    }

    private static void keepLatest(Map<String, Departure> byTrain, Departure incoming) {
        Departure kept = byTrain.get(incoming.trainNo());
        if (kept == null || (kept.source() == incoming.source() && incoming.departedAt().isAfter(kept.departedAt()))) {
            byTrain.put(incoming.trainNo(), incoming);
        }
    }
}
