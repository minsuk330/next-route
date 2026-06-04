package watoo.grd.nextroute.application.bus.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.bus.dto.BusRidershipFetchResult;
import watoo.grd.nextroute.application.bus.dto.BusRidershipInfo;
import watoo.grd.nextroute.application.bus.dto.BusRouteRidershipRank;
import watoo.grd.nextroute.application.bus.dto.BusRouteRidershipRankingResponse;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusRidershipRankingService {

	private final BusApiPort busApiPort;

	public BusRouteRidershipRankingResponse findTopRoutes(String month, int limit, int pageSize) {
		validate(month, limit, pageSize);

		BusRidershipFetchResult fetchResult = busApiPort.getBusRidershipByMonth(month, pageSize);
		List<BusRouteRidershipRank> rankings = rank(fetchResult.rows(), limit);

		log.info("[BusRidership] month={}, fetchedRows={}, totalRows={}, rankingSize={}",
				month, fetchResult.fetchedRowCount(), fetchResult.totalRowCount(), rankings.size());

		return new BusRouteRidershipRankingResponse(
				month,
				fetchResult.totalRowCount(),
				fetchResult.fetchedRowCount(),
				rankings
		);
	}

	private void validate(String month, int limit, int pageSize) {
		if (month == null || !month.matches("\\d{6}")) {
			throw new IllegalArgumentException("month must be yyyyMM");
		}
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be positive");
		}
		if (pageSize <= 0) {
			throw new IllegalArgumentException("pageSize must be positive");
		}
	}

	private List<BusRouteRidershipRank> rank(List<BusRidershipInfo> rows, int limit) {
		Map<RouteKey, RouteAggregate> aggregates = new LinkedHashMap<>();

		for (BusRidershipInfo row : rows) {
			RouteKey key = new RouteKey(row.routeNo(), row.routeName());
			aggregates.computeIfAbsent(key, unused -> new RouteAggregate(row.routeNo(), row.routeName()))
					.add(row);
		}

		List<RouteAggregate> sorted = aggregates.values().stream()
				.sorted(Comparator.comparingLong(RouteAggregate::totalUsage).reversed()
						.thenComparing(RouteAggregate::routeNo, Comparator.nullsLast(String::compareTo))
						.thenComparing(RouteAggregate::routeName, Comparator.nullsLast(String::compareTo)))
				.limit(limit)
				.toList();

		List<BusRouteRidershipRank> rankings = new ArrayList<>();
		for (int i = 0; i < sorted.size(); i++) {
			RouteAggregate aggregate = sorted.get(i);
			rankings.add(new BusRouteRidershipRank(
					i + 1,
					aggregate.routeNo(),
					aggregate.routeName(),
					aggregate.getOnTotal(),
					aggregate.getOffTotal(),
					aggregate.totalUsage(),
					aggregate.rowCount()
			));
		}
		return rankings;
	}

	private record RouteKey(String routeNo, String routeName) {}

	private static class RouteAggregate {
		private final String routeNo;
		private final String routeName;
		private long getOnTotal;
		private long getOffTotal;
		private int rowCount;

		private RouteAggregate(String routeNo, String routeName) {
			this.routeNo = routeNo;
			this.routeName = routeName;
		}

		private void add(BusRidershipInfo row) {
			this.getOnTotal += row.getOnTotal();
			this.getOffTotal += row.getOffTotal();
			this.rowCount++;
		}

		private String routeNo() {
			return routeNo;
		}

		private String routeName() {
			return routeName;
		}

		private long getOnTotal() {
			return getOnTotal;
		}

		private long getOffTotal() {
			return getOffTotal;
		}

		private long totalUsage() {
			return getOnTotal + getOffTotal;
		}

		private int rowCount() {
			return rowCount;
		}
	}
}
