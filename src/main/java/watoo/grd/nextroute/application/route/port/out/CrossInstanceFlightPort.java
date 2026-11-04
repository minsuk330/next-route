package watoo.grd.nextroute.application.route.port.out;

import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.Outcome;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 서버 사이의 single-flight. 같은 키의 캐시 miss 가 api 와 worker 마다 공급자를 부르지 않게, 한 대만 리더로 부르고
 * 나머지는 리더가 남긴 <b>결과 키</b>를 읽는다.
 *
 * <p>결과는 캐시가 아니라 토큰별 결과 키({@code …:{token}})로 전한다. 캐시 쓰기 실패나 "빈 목록은 캐시 안 씀" 규칙에
 * 기대지 않고, 이전 flight 의 실패를 새 flight 의 대기자가 잘못 읽지 않게 하려는 것이다(플랜 검토 #2, #3).
 *
 * <p>Redis 오류는 예외로 올린다 — 호출자가 "직접 부른다"로 내려앉힌다.
 */
public interface CrossInstanceFlightPort {

    /** @param mine true 면 token 은 내 것(리더). false 면 현재 보유자의 토큰(null 이면 잠금이 그 사이 사라짐). */
    record Lead(boolean mine, String token) {}

    /** 리더가 남긴 결과. outcome 이 OK 일 때만 data 가 의미 있다. */
    record Peek<T>(Outcome outcome, List<T> data) {}

    Lead tryLead(String type, String key, Duration lockTtl);

    /** 결과 키를 쓰고 내 잠금을 푼다(토큰이 같을 때만). */
    void finish(String type, String key, String token, Outcome outcome, List<?> data, Duration resultTtl);

    <T> Optional<Peek<T>> peekResult(String type, String key, String token, Class<T> elementType);

    /** 지금 잠금을 쥔 토큰. 없으면 empty. 대기자가 리더 교체(만료 뒤 새 리더)를 따라가는 데 쓴다. */
    Optional<String> currentHolder(String type, String key);

    /** 플래그 꺼짐용 — 늘 "내가 리더". 서버 한 대 동작과 같다. */
    static CrossInstanceFlightPort none() {
        return new CrossInstanceFlightPort() {
            @Override
            public Lead tryLead(String type, String key, Duration lockTtl) {
                return new Lead(true, "local");
            }

            @Override
            public void finish(String type, String key, String token, Outcome outcome, List<?> data,
                               Duration resultTtl) {
            }

            @Override
            public <T> Optional<Peek<T>> peekResult(String type, String key, String token, Class<T> elementType) {
                return Optional.empty();
            }

            @Override
            public Optional<String> currentHolder(String type, String key) {
                return Optional.empty();
            }
        };
    }
}
