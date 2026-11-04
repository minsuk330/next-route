package watoo.grd.nextroute.application.route.concurrent;

import lombok.extern.slf4j.Slf4j;
import watoo.grd.nextroute.application.route.port.out.CrossInstanceFlightPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.Outcome;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 서버 사이 single-flight 의 공통 절차(bus-multi-instance PR-2 의 {@code CachedSearchTimeBusAdapter} 로직을 PR-4 에서 서울·경기·TAGO 가
 * 같이 쓰게 뽑은 것). JVM 안 {@link SingleFlight} 의 owner 만 여기 들어온다(2단계).
 *
 * <p>리더: 잠금을 잡은 뒤 캐시를 <b>한 번 더</b> 읽는다(double-check). 공급자를 부른 뒤 outcome 과 data 를 토큰별 결과 키에
 * 남기고 잠금을 푼다. 대기자: 보유자 토큰의 결과 키를 {@code pollMs} 마다 본다. OK → cached, BLOCKED → 그대로(공유 차단기),
 * LIMITED·ERROR → 직접 부른다(리더 서버만의 사정일 수 있어 플래그 꺼짐과 같은 가용성). 리더가 바뀌면 새 토큰을 따라간다.
 * 잠금도 결과도 없으면(리더 사망) 스스로 리더를 시도한다 — 잡으면 직접 부르고, 남이 잡았으면 따라간다. 상한 초과·Redis 오류는 LIMITED. 잠금 획득 자체가
 * Redis 오류면 직접 부른다.
 */
@Slf4j
public final class CrossInstanceFlightRunner {

    /** 지표 훅 — role = leader / leader_recheck_hit / waiter_result / waiter_fallback / waiter_takeover / waiter_timeout / redis_error. */
    @FunctionalInterface
    public interface Recorder {
        void record(String type, String role);
    }

    private final CrossInstanceFlightPort flight;
    private final Recorder recorder;
    private final long pollMs;
    private final Duration resultTtl;

    public CrossInstanceFlightRunner(CrossInstanceFlightPort flight, Recorder recorder, long pollMs, Duration resultTtl) {
        this.flight = flight;
        this.recorder = recorder;
        this.pollMs = Math.max(1, pollMs);
        this.resultTtl = resultTtl;
    }

    public <T> BusQueryResult<T> run(String type, String key, Supplier<BusQueryResult<T>> loader,
                                     Supplier<Optional<List<T>>> cacheProbe, Class<T> elementType,
                                     Duration lockTtl, long waitMs) {
        CrossInstanceFlightPort.Lead lead;
        try {
            lead = flight.tryLead(type, key, lockTtl);
        } catch (RuntimeException e) {
            log.warn("[CrossFlight] 잠금 시도 실패 — 직접 부른다 [{}:{}]: {}", type, key, e.getMessage());
            recorder.record(type, "redis_error");
            return loader.get();
        }
        if (lead.mine()) {
            return lead(type, key, lead.token(), loader, cacheProbe);
        }
        return await(type, key, lead.token(), loader, cacheProbe, elementType, lockTtl, waitMs);
    }

    private <T> BusQueryResult<T> lead(String type, String key, String token, Supplier<BusQueryResult<T>> loader,
                                       Supplier<Optional<List<T>>> cacheProbe) {
        Optional<List<T>> again = cacheProbe.get();
        if (again.isPresent()) {
            recorder.record(type, "leader_recheck_hit");
            finishQuietly(type, key, token, Outcome.OK, again.get());
            return BusQueryResult.cached(again.get());
        }
        BusQueryResult<T> fresh;
        try {
            fresh = loader.get();
        } catch (RuntimeException e) {
            finishQuietly(type, key, token, Outcome.ERROR, List.of());
            throw e;
        }
        recorder.record(type, "leader");
        finishQuietly(type, key, token, fresh.outcome(), fresh.data());
        return fresh;
    }

    private void finishQuietly(String type, String key, String token, Outcome outcome, List<?> data) {
        try {
            flight.finish(type, key, token, outcome, data, resultTtl);
        } catch (RuntimeException e) {
            log.warn("[CrossFlight] 결과 기록 실패 [{}:{}]: {}", type, key, e.getMessage());
            recorder.record(type, "redis_error");
        }
    }

    private <T> BusQueryResult<T> await(String type, String key, String holder, Supplier<BusQueryResult<T>> loader,
                                        Supplier<Optional<List<T>>> cacheProbe, Class<T> elementType,
                                        Duration lockTtl, long waitMs) {
        long deadline = System.nanoTime() + waitMs * 1_000_000L;
        while (true) {
            try {
                Optional<CrossInstanceFlightPort.Peek<T>> peek = holder == null
                        ? Optional.empty()
                        : flight.peekResult(type, key, holder, elementType);
                if (peek.isEmpty()) {
                    Optional<String> current = flight.currentHolder(type, key);
                    if (current.isEmpty()) {
                        peek = holder == null ? Optional.empty() : flight.peekResult(type, key, holder, elementType);
                        if (peek.isEmpty()) {
                            CrossInstanceFlightPort.Lead retake = flight.tryLead(type, key, lockTtl);
                            if (retake.mine()) {
                                recorder.record(type, "waiter_takeover");
                                return lead(type, key, retake.token(), loader, cacheProbe);
                            }
                            holder = retake.token();
                        }
                    } else if (holder == null || !current.get().equals(holder)) {
                        holder = current.get();
                    }
                }
                if (peek.isPresent()) {
                    CrossInstanceFlightPort.Peek<T> r = peek.get();
                    return switch (r.outcome()) {
                        case OK -> {
                            recorder.record(type, "waiter_result");
                            yield BusQueryResult.cached(r.data());
                        }
                        case BLOCKED -> {
                            recorder.record(type, "waiter_result");
                            yield BusQueryResult.blocked();
                        }
                        case LIMITED, ERROR -> {
                            recorder.record(type, "waiter_fallback");
                            yield loader.get();
                        }
                    };
                }
            } catch (RuntimeException e) {
                log.warn("[CrossFlight] 결과 조회 실패 [{}:{}]: {}", type, key, e.getMessage());
                recorder.record(type, "redis_error");
                return BusQueryResult.limited();
            }
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) {
                recorder.record(type, "waiter_timeout");
                return BusQueryResult.limited();
            }
            try {
                Thread.sleep(Math.min(pollMs, remainingMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return BusQueryResult.limited();
            }
        }
    }
}
