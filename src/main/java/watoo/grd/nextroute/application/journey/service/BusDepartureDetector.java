package watoo.grd.nextroute.application.journey.service;

import watoo.grd.nextroute.application.bus.port.out.BusPositionPort.Snapshot;
import watoo.grd.nextroute.application.bus.port.out.BusPositionPort.Vehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 같은 노선의 연속 두 위치 관측에서 <b>승차 정류장을 떠난 차</b>를 찾는다(#265). 순수 함수다.
 *
 * <p>떠났다 = 앞 관측에서 아직 안 지났고(승차 정류장에 서 있거나 다가오는 중) 지금 관측에서 지났다
 * ({@link Vehicle#isPast}). 서울은 정류소 도착(stopFlag 1)에서 운행(0)으로, 경기는 정류소 출발(stateCd 2) 또는 순번 증가로,
 * TAGO 는 순번 증가로만 드러난다. 출발 시각 = 지금 관측 시각(처음 본 시각) — 조회 간격만큼 늦고, 대조 창이 흡수한다.
 *
 * <p>앞 관측에 없던 차(처음 본 차)는 사건을 만들지 않는다 — 언제 떠났는지 모른다. 앞 관측에서 승차 정류장보다
 * {@code approachStops} 넘게 멀었던 차도 뺀다(한 번에 여러 정류장을 건너뛴 관측은 출발 시각을 말해 주지 않는다).
 */
public final class BusDepartureDetector {

    private BusDepartureDetector() {
    }

    public static List<BusVehicleMatcher.Departure> departures(Snapshot previous, Snapshot current, int boardSeq,
                                                               int approachStops) {
        if (previous == null || !previous.isOk() || !current.isOk()) {
            return List.of();
        }
        Map<String, Vehicle> before = previous.vehicles().stream()
                .collect(Collectors.toMap(Vehicle::vehicleId, Function.identity(), (a, b) -> a));
        List<BusVehicleMatcher.Departure> found = new ArrayList<>();
        for (Vehicle now : current.vehicles()) {
            Vehicle was = before.get(now.vehicleId());
            if (was == null || was.isPast(boardSeq) || !now.isPast(boardSeq)) {
                continue;
            }
            if (was.seq() < boardSeq - approachStops) {
                continue;
            }
            found.add(new BusVehicleMatcher.Departure(now.vehicleId(), now.plateNo(), current.observedAt()));
        }
        return found;
    }

    /** 승차 정류장 {@code nearStops} 정거장 안에 아직 안 지난 차가 있다(근접 관찰로 바꿀 때). */
    public static boolean approaching(Snapshot snapshot, int boardSeq, int nearStops) {
        return snapshot.isOk() && snapshot.vehicles().stream()
                .anyMatch(v -> !v.isPast(boardSeq) && v.seq() >= boardSeq - nearStops);
    }
}
