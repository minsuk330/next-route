package watoo.grd.nextroute.infrastructure.adapter.out.api.bus;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.bus.port.out.BusApiBreakerPort;
import watoo.grd.nextroute.application.bus.port.out.BusApiQuotaPort;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;
import watoo.grd.nextroute.common.config.ClockConfig;
import watoo.grd.nextroute.infrastructure.adapter.out.api.bus.dto.BusApiResponse;
import watoo.grd.nextroute.infrastructure.adapter.out.api.bus.dto.BusArrivalItem;
import watoo.grd.nextroute.infrastructure.adapter.out.api.bus.dto.BusPositionItem;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static watoo.grd.nextroute.common.util.ParseUtils.parseDouble;
import static watoo.grd.nextroute.common.util.ParseUtils.parseInteger;

/**
 * 경로검색 전용 버스 실시간 조회 어댑터.
 * 재시도 없음, ~1.5s timeout. 검색 레이턴시 보호용.
 * 기존 SeoulBusApiAdapter(3회 재시도/30s)는 그대로 유지.
 *
 * <p>collector와 동일 provider key를 쓰므로 운영 안전장치를 적용한다(검색은 어떤 경우에도 깨지 않음 — 빈 결과):
 * <ul>
 *   <li>공유 {@link BusApiBreakerPort} 차단 중이면 외부 호출 생략. error code 7 수신 시 공유 차단을 건다.</li>
 *   <li>{@link BusApiQuotaPort} 검색 몫 quota 소진 시 호출 생략(collector quota 잠식 방지).</li>
 *   <li>전역 {@link Semaphore}로 fan-out 동시 호출 수 제한(슬롯 대기 초과 시 생략).</li>
 * </ul>
 */
@Slf4j
@Component
public class SearchTimeBusAdapter implements SearchTimeBusQueryPort {

    private static final String API_LIMIT_EXCEEDED_CODE = "7";

    private final RestClient restClient;
    private final XmlMapper xmlMapper;
    private final String apiKey;
    private final String baseUrl;
    private final BusApiBreakerPort breaker;
    private final BusApiQuotaPort quota;
    private final Semaphore concurrencyLimiter;
    private final TransferArrivalProperties props;
    private final Clock clock;

    public SearchTimeBusAdapter(
            @Qualifier("busRealtimeRestClient") RestClient restClient,
            BusApiBreakerPort breaker,
            BusApiQuotaPort quota,
            @Qualifier("busSearchConcurrencyLimiter") Semaphore concurrencyLimiter,
            TransferArrivalProperties props,
            Clock clock,
            @Value("${seoul.api.bus-key}") String apiKey,
            @Value("${seoul.api.bus-base-url}") String baseUrl) {
        this.restClient = restClient;
        this.breaker = breaker;
        this.quota = quota;
        this.concurrencyLimiter = concurrencyLimiter;
        this.props = props;
        this.clock = clock;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.xmlMapper = new XmlMapper();
        this.xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.xmlMapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
    }

    @Override
    public BusQueryResult<BusArrivalInfo> getArrInfoByStop(String stopId, String routeId, int ord) {
        URI uri = URI.create(baseUrl + "/arrive/getArrInfoByRoute"
                + "?serviceKey=" + apiKey
                + "&stId=" + stopId
                + "&busRouteId=" + routeId
                + "&ord=" + ord);
        return callApi(uri, BusArrivalItem.class, BusApiQuotaPort.Endpoint.ARRIVAL, this::toArrivalInfo);
    }

    @Override
    public BusQueryResult<BusPositionInfo> getBusPosByRtid(String busRouteId) {
        URI uri = URI.create(baseUrl + "/buspos/getBusPosByRtid"
                + "?serviceKey=" + apiKey
                + "&busRouteId=" + busRouteId);
        return callApi(uri, BusPositionItem.class, BusApiQuotaPort.Endpoint.POSITION, this::toPositionInfo);
    }

    private <I, R> BusQueryResult<R> callApi(URI uri, Class<I> itemType,
                                             BusApiQuotaPort.Endpoint endpoint,
                                             java.util.function.Function<I, R> mapper) {
        // 1. 공유 차단 중이면 외부 호출 생략 → BLOCKED
        Optional<Instant> blocked = breaker.getBlockedUntil();
        if (blocked.isPresent()) {
            log.debug("[SearchTimeAdapter] breaker blocked until {} — skip {}", blocked.get(), uri.getPath());
            return BusQueryResult.blocked();
        }
        // 2. 동시 호출 슬롯 확보(초과 대기 시 생략 → LIMITED)
        boolean acquired;
        try {
            acquired = concurrencyLimiter.tryAcquire(props.getExternalCallAcquireMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return BusQueryResult.limited();
        }
        if (!acquired) {
            log.debug("[SearchTimeAdapter] concurrency slot unavailable — skip {}", uri.getPath());
            return BusQueryResult.limited();
        }
        try {
            // 3. 검색 몫 quota(collector quota 잠식 방지). 소진/장애 시 생략(fail-closed) → LIMITED
            if (!quota.tryAcquireSearch(endpoint)) {
                log.debug("[SearchTimeAdapter] search quota exhausted [{}] — skip {}", endpoint, uri.getPath());
                return BusQueryResult.limited();
            }
            return doCall(uri, itemType, mapper);
        } finally {
            concurrencyLimiter.release();
        }
    }

    private <I, R> BusQueryResult<R> doCall(URI uri, Class<I> itemType,
                                            java.util.function.Function<I, R> mapper) {
        try {
            String xml = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);
            if (xml == null || xml.isBlank()) {
                log.warn("[SearchTimeAdapter] Empty response: {}", uri.getPath());
                return BusQueryResult.ok(List.of());
            }
            JavaType type = xmlMapper.getTypeFactory()
                    .constructParametricType(BusApiResponse.class, itemType);
            BusApiResponse<I> response = xmlMapper.readValue(xml, type);
            if (!response.isSuccess()) {
                String cd = response.getMsgHeader() == null ? null : response.getMsgHeader().getHeaderCd();
                String msg = response.getMsgHeader() == null ? null : response.getMsgHeader().getHeaderMsg();
                if (API_LIMIT_EXCEEDED_CODE.equals(cd == null ? null : cd.trim())) {
                    // 공유 차단(collector·search 함께 다음날 자정까지 차단) → BLOCKED
                    breaker.tripUntil(nextMidnight());
                    log.error("[SearchTimeAdapter] API error code 7 — tripping shared breaker [{}]: {}",
                            uri.getPath(), msg);
                    return BusQueryResult.blocked();
                }
                log.warn("[SearchTimeAdapter] API error [{}]: {} - {}", uri.getPath(), cd, msg);
                return BusQueryResult.error();
            }
            List<R> mapped = response.getItems().stream().map(mapper).toList();
            return BusQueryResult.ok(mapped);
        } catch (Exception e) {
            log.warn("[SearchTimeAdapter] Call failed [{}]: {}", uri.getPath(), e.getMessage());
            return BusQueryResult.error();
        }
    }

    private Instant nextMidnight() {
        return ZonedDateTime.now(clock)
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(ClockConfig.KST)
                .toInstant();
    }

    private BusArrivalInfo toArrivalInfo(BusArrivalItem item) {
        return new BusArrivalInfo(
                item.getBusRouteId(), item.getBusRouteAbrv(), item.getRtNm(),
                item.getStId(), item.getArsId(), item.getStNm(),
                parseInteger(item.getStaOrd()), item.getDir(),
                parseInteger(item.getRouteType()), parseInteger(item.getTerm()),
                item.getMkTm(), item.getDeTourAt(), item.getNextBus(),
                item.getFirstTm(), item.getLastTm(),
                item.getArrmsg1(), item.getVehId1(), item.getPlainNo1(),
                parseInteger(item.getBusType1()), parseInteger(item.getSectOrd1()),
                item.getStationNm1(), item.getIsArrive1(), item.getIsLast1(), item.getFull1(),
                parseInteger(item.getExps1()), parseInteger(item.getKals1()),
                parseInteger(item.getNeus1()), parseInteger(item.getGoal1()),
                parseDouble(item.getAvgCf1()), parseDouble(item.getExpCf1()),
                parseDouble(item.getKalCf1()), parseDouble(item.getNeuCf1()),
                parseInteger(item.getTraTime1()), parseDouble(item.getTraSpd1()),
                parseInteger(item.getBrdrdeNum1()), parseInteger(item.getBrerdDiv1()),
                parseInteger(item.getRerideNum1()), parseInteger(item.getRerdieDiv1()),
                item.getNstnId1(), parseInteger(item.getNstnOrd1()),
                parseInteger(item.getNstnSec1()), parseInteger(item.getNstnSpd1()),
                parseInteger(item.getNmainOrd1()), parseInteger(item.getNmainSec1()),
                item.getNmainStnid1(), parseInteger(item.getNmain2Ord1()),
                parseInteger(item.getNamin2Sec1()), item.getNmain2Stnid1(),
                parseInteger(item.getNmain3Ord1()), parseInteger(item.getNmain3Sec1()),
                item.getNmain3Stnid1(),
                item.getArrmsg2(), item.getVehId2(), item.getPlainNo2(),
                parseInteger(item.getBusType2()), parseInteger(item.getSectOrd2()),
                item.getStationNm2(), item.getIsArrive2(), item.getIsLast2(), item.getFull2(),
                parseInteger(item.getExps2()), parseInteger(item.getKals2()),
                parseInteger(item.getNeus2()), parseInteger(item.getGoal2()),
                parseDouble(item.getAvgCf2()), parseDouble(item.getExpCf2()),
                parseDouble(item.getKalCf2()), parseDouble(item.getNeuCf2()),
                parseInteger(item.getTraTime2()), parseDouble(item.getTraSpd2()),
                parseInteger(item.getBrdrdeNum2()), parseInteger(item.getBrerdDiv2()),
                parseInteger(item.getRerideNum2()), parseInteger(item.getRerdieDiv2()),
                item.getNstnId2(), parseInteger(item.getNstnOrd2()),
                parseInteger(item.getNstnSec2()), parseInteger(item.getNstnSpd2()),
                parseInteger(item.getNmainOrd2()), parseInteger(item.getNmainSec2()),
                item.getNmainStnid2(), parseInteger(item.getNmain2Ord2()),
                parseInteger(item.getNamin2Sec2()), item.getNmain2Stnid2(),
                parseInteger(item.getNmain3Ord2()), parseInteger(item.getNmain3Sec2()),
                item.getNmain3Stnid2()
        );
    }

    private BusPositionInfo toPositionInfo(BusPositionItem item) {
        return new BusPositionInfo(
                item.getVehId(), parseInteger(item.getNextStTm()),
                parseInteger(item.getSectOrd()), parseDouble(item.getSectDist()),
                parseDouble(item.getRtDist()), item.getStopFlag(), item.getSectionId(),
                item.getDataTm(), item.getPlainNo(), parseInteger(item.getBusType()),
                parseInteger(item.getLastStTm()), item.getLastStnId(),
                parseDouble(item.getPosX()), parseDouble(item.getPosY()),
                item.getIsFullFlag(), item.getIslastyn(),
                parseDouble(item.getFullSectDist()), item.getNextStId(),
                parseInteger(item.getCongetion()), item.getTrnstnid(),
                parseDouble(item.getGpsX()), parseDouble(item.getGpsY()),
                item.getIsrunyn()
        );
    }
}
