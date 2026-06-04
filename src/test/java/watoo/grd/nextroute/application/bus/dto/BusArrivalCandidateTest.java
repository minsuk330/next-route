package watoo.grd.nextroute.application.bus.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static watoo.grd.nextroute.application.bus.BusArrivalInfoFixtures.arrivalInfo;

class BusArrivalCandidateTest {

	@Test
	void TC_API_row의_1번_2번_도착정보를_candidate로_분리한다() {
		LocalDateTime collectedAt = LocalDateTime.of(2026, 6, 4, 10, 0);

		List<BusArrivalCandidate> candidates = BusArrivalCandidate.from(
				arrivalInfo("veh-1", "서울71사1111", "1분 후", "veh-2", "서울72사2222", "4분 후"),
				collectedAt
		);

		assertThat(candidates).hasSize(2);
		assertThat(candidates).extracting(BusArrivalCandidate::arrivalOrder)
				.containsExactly(1, 2);
		assertThat(candidates).extracting(BusArrivalCandidate::vehicleIdentity)
				.containsExactly("veh-1", "veh-2");
		assertThat(candidates).extracting(BusArrivalCandidate::identityKey)
				.containsExactly("veh:veh-1", "veh:veh-2");
		assertThat(candidates.get(0).predictTime()).isEqualTo(60);
		assertThat(candidates.get(1).predictTime()).isEqualTo(240);
	}

	@Test
	void TC_vehId가_유효하지_않으면_plainNo를_identity로_사용한다() {
		LocalDateTime collectedAt = LocalDateTime.of(2026, 6, 4, 10, 0);

		List<BusArrivalCandidate> candidates = BusArrivalCandidate.from(
				arrivalInfo("0", "서울71사1111", "1분 후", " ", "서울72사2222", "4분 후"),
				collectedAt
		);

		assertThat(candidates).hasSize(2);
		assertThat(candidates).extracting(BusArrivalCandidate::vehicleIdentityType)
				.containsExactly(VehicleIdentityType.PLAIN_NO, VehicleIdentityType.PLAIN_NO);
		assertThat(candidates).extracting(BusArrivalCandidate::identityKey)
				.containsExactly("plate:서울71사1111", "plate:서울72사2222");
	}

	@Test
	void TC_vehId와_plainNo가_모두_유효하지_않으면_candidate를_skip한다() {
		LocalDateTime collectedAt = LocalDateTime.of(2026, 6, 4, 10, 0);

		List<BusArrivalCandidate> candidates = BusArrivalCandidate.from(
				arrivalInfo("0", "0", "출발대기", null, " ", "출발대기"),
				collectedAt
		);

		assertThat(candidates).isEmpty();
	}
}
