package watoo.grd.nextroute.application.journey.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 재탐색 대안 후보의 <b>기본 출처</b> — 저장값과 OTP pattern 을 게이트에 따라 합친다(national-otp P2-F PR-4).
 *
 * <p>{@link RouteOptionCandidateProvider} 는 후보가 어디서 왔는지 모른 채 이 하나만 주입받는다
 * ({@code @Primary}). 갈래는 여기서 가른다.
 *
 * <h2>왜 순수 대체가 아니라 합집합인가 (codex 리뷰 B3)</h2>
 * <ul>
 *   <li><b>기준선이 저장에서 나온다.</b> 소비자는 저장 {@code selected} 노선을 기준선으로 삼아
 *       개선폭을 잰다. OTP 만 쓰면 기준선을 찾지 못해 이 칸이 통째로 빈손이 된다.</li>
 *   <li><b>매핑이 다 되지 않는다.</b> OTP route id 는 스코프 밖(경기·인천)·방향 suffix 로 서울
 *       노선에 매핑되지 않는 것이 많다. OTP 만 쓰면 저장값보다 후보가 <b>줄 수도</b> 있다.</li>
 *   <li><b>OTP 는 꺼지거나 죽는다.</b> 그때 저장값이 답을 이어야 한다.</li>
 * </ul>
 *
 * <p>그래서 게이트가 켜져도 저장값을 버리지 않는다. 저장값 ∪ (OTP 가 새로 찾은 것)이고, OTP 가
 * 실패하면(off·timeout·error) 저장값만 돌려준다.
 */
@Slf4j
@Component
@Primary
public class UnionRouteOptionSource implements RerouteRouteOptionSource {

    private final StoredRouteOptionSource stored;
    private final OtpPatternRouteOptionSource otp;
    private final JourneyRerouteProperties properties;

    public UnionRouteOptionSource(StoredRouteOptionSource stored, OtpPatternRouteOptionSource otp,
                                  JourneyRerouteProperties properties) {
        this.stored = stored;
        this.otp = otp;
        this.properties = properties;
    }

    @Override
    public SourceOutcome optionsFor(JourneyLeg waitLeg, RoutingDeadline deadline) {
        SourceOutcome storedOutcome = stored.optionsFor(waitLeg, deadline);
        if (!properties.getRouteOption().isOtpPatternEnabled()) {
            // 게이트 꺼짐 = 지금까지와 동일(저장값만). PR-2 의 무행위변경이 여기서 성립한다.
            return storedOutcome;
        }

        SourceOutcome otpOutcome = otp.optionsFor(waitLeg, deadline);
        if (!otpOutcome.isOk()) {
            // OTP 가 답을 못 했다(off·timeout·error). 저장값으로 물러선다 — 후보가 줄지언정 죽지 않는다.
            log.debug("[JourneyReroute] OTP pattern 출처 비정상({}) → 저장값 폴백 legId={}",
                    otpOutcome.status(), waitLeg.getId());
            return storedOutcome;
        }

        return SourceOutcome.ok(union(storedOutcome.options(), otpOutcome.options()));
    }

    /**
     * 저장값 뒤에 OTP 후보를 잇되, 저장에 이미 있는 {@code (routeId, boardingSeq)} 는 뺀다.
     *
     * <p>키가 {@code (routeId, boardingSeq)} 인 이유(codex B5/B8): {@code directionKey} 는 방향
     * ({@code direction_id})가 feed 에 없어 dedup 근거가 못 되고, id 로 빼면 저장·OTP 가 서로 다른
     * 임시 id 라 같은 노선이 중복된다. 특히 <b>기준선(지금 타는 노선)</b>이 OTP 후보로 다시 나와
     * "지금 노선으로 갈아타세요"가 되는 것을 막는다.
     */
    private List<JourneyLegRouteOption> union(List<JourneyLegRouteOption> storedOptions,
                                              List<JourneyLegRouteOption> otpOptions) {
        Set<String> seen = new HashSet<>();
        for (JourneyLegRouteOption option : storedOptions) {
            seen.add(key(option));
        }
        List<JourneyLegRouteOption> merged = new ArrayList<>(storedOptions);
        for (JourneyLegRouteOption option : otpOptions) {
            if (seen.add(key(option))) {
                merged.add(option);
            }
        }
        return merged;
    }

    private String key(JourneyLegRouteOption option) {
        return option.getRouteId() + "|" + option.getBoardingSeq();
    }
}
