package watoo.grd.nextroute.application.bus.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.bus.config.RouteRotationProperties;
import watoo.grd.nextroute.application.bus.dto.BusRouteRidershipRank;
import watoo.grd.nextroute.application.bus.dto.BusRouteRidershipRankingResponse;
import watoo.grd.nextroute.domain.bus.entity.RouteRotationState;
import watoo.grd.nextroute.domain.bus.entity.WeeklyTargetRoute;
import watoo.grd.nextroute.domain.bus.repository.RouteRotationStateRepository;
import watoo.grd.nextroute.domain.bus.repository.WeeklyTargetRouteRepository;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WeeklyRouteRotationServiceTest {

	@Mock BusRidershipRankingService rankingService;
	@Mock WeeklyTargetRouteRepository targetRouteRepository;
	@Mock RouteRotationStateRepository stateRepository;

	RouteRotationProperties properties = new RouteRotationProperties();
	Clock clock = Clock.fixed(ZonedDateTime.of(2026, 3, 16, 4, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant(),
			ZoneId.of("Asia/Seoul"));
	WeeklyRouteRotationService service;

	@BeforeEach
	void setUp() {
		properties.setRidershipMonth("202603");
		properties.setLimit(30);
		properties.setPageSize(1000);
		service = new WeeklyRouteRotationService(
				rankingService, targetRouteRepository, stateRepository, properties, clock);
	}

	private BusRouteRidershipRankingResponse ranking(BusRouteRidershipRank... ranks) {
		return new BusRouteRidershipRankingResponse("202603", 100, 100, List.of(ranks));
	}

	private BusRouteRidershipRank rank(int r, String no, String name) {
		return new BusRouteRidershipRank(r, no, name, 10, 10, 20, 1);
	}

	@Test
	void TC_다음_bucket으로_active를_교체하고_state를_전진시킨다() {
		given(stateRepository.findFirstByDeletedAtIsNullOrderByIdAsc())
				.willReturn(Optional.of(RouteRotationState.builder()
						.currentBucket(0).ridershipMonth("202603").build()));
		given(rankingService.findTopRoutes("202603", 30, 30, 1000))
				.willReturn(ranking(rank(31, "500", "500"), rank(32, "501", "501")));
		given(targetRouteRepository.findByActiveTrueAndDeletedAtIsNull()).willReturn(List.of());

		WeeklyRouteRotationService.RotationResult result = service.rotate();

		assertThat(result.bucket()).isEqualTo(1);
		assertThat(result.offset()).isEqualTo(30);
		assertThat(result.routeCount()).isEqualTo(2);
		assertThat(result.wrapped()).isFalse();

		ArgumentCaptor<List<WeeklyTargetRoute>> saved = ArgumentCaptor.forClass(List.class);
		verify(targetRouteRepository, org.mockito.Mockito.atLeastOnce()).saveAll(saved.capture());
		List<WeeklyTargetRoute> inserted = saved.getAllValues().get(saved.getAllValues().size() - 1);
		assertThat(inserted).extracting(WeeklyTargetRoute::getRouteName).containsExactly("500", "501");
		assertThat(inserted).allMatch(WeeklyTargetRoute::isActive);
		assertThat(inserted).allMatch(r -> r.getBucket() == 1);
	}

	@Test
	void TC_다음_bucket이_비면_bucket0으로_순환한다() {
		given(stateRepository.findFirstByDeletedAtIsNullOrderByIdAsc())
				.willReturn(Optional.of(RouteRotationState.builder()
						.currentBucket(3).ridershipMonth("202603").build()));
		given(rankingService.findTopRoutes("202603", 30, 120, 1000))
				.willReturn(ranking()); // empty → wrap
		given(rankingService.findTopRoutes("202603", 30, 0, 1000))
				.willReturn(ranking(rank(1, "143", "143")));
		given(targetRouteRepository.findByActiveTrueAndDeletedAtIsNull()).willReturn(List.of());

		WeeklyRouteRotationService.RotationResult result = service.rotate();

		assertThat(result.wrapped()).isTrue();
		assertThat(result.bucket()).isZero();
		assertThat(result.offset()).isZero();
		assertThat(result.routeCount()).isEqualTo(1);
	}

	@Test
	void TC_state가_없으면_생성하고_bucket1로_전진() {
		RouteRotationState seeded = RouteRotationState.builder()
				.currentBucket(0).ridershipMonth("202603").build();
		given(stateRepository.findFirstByDeletedAtIsNullOrderByIdAsc()).willReturn(Optional.empty());
		given(stateRepository.save(org.mockito.ArgumentMatchers.any(RouteRotationState.class)))
				.willReturn(seeded);
		given(rankingService.findTopRoutes(eq("202603"), eq(30), anyInt(), eq(1000)))
				.willReturn(ranking(rank(31, "500", "500")));
		given(targetRouteRepository.findByActiveTrueAndDeletedAtIsNull()).willReturn(List.of());

		WeeklyRouteRotationService.RotationResult result = service.rotate();

		assertThat(result.bucket()).isEqualTo(1);
	}
}
