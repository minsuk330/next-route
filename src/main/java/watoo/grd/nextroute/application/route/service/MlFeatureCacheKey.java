package watoo.grd.nextroute.application.route.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * ML 예측 캐시 키. <b>feature 값 전체</b>에서 만든다.
 *
 * <p>같은 입력이면 같은 예측이라는 것이 모델의 정의다. 그래서 키를 입력 그 자체로 잡으면 캐시가
 * 틀릴 수가 없다. {@code routeId:vehicleId:targetSeq} 같은 조합 키는 "이 조합이면 답이 같다"는 별도
 * 가정을 필요로 하고, 그 가정이 깨지는 순간(feature 가 하나 늘어날 때) 조용히 옛 답을 준다.
 *
 * <p>스스로 만료된다는 점이 덤이다. 시간 feature 는 차량 관측 시각({@code dataTm})에서, 날씨·휴일
 * feature 는 폴링 캐시에서 나오므로 <b>상류가 갱신되면 키가 저절로 바뀐다.</b> TTL 은 메모리 상한일
 * 뿐 신선도를 책임지지 않는다.
 *
 * <p>서로 다른 차량이 우연히 같은 feature 를 가지면 키도 같지만, 그때는 예측도 같으므로 무해하다.
 */
public final class MlFeatureCacheKey {

    /** 키 길이. SHA-256 앞 절반이면 충돌 확률이 무시할 수준이고 Redis 키가 짧아진다. */
    private static final int KEY_LENGTH = 32;

    private MlFeatureCacheKey() {
    }

    /**
     * @param features {@code MlFeatureVector.features}. 값에 null 이 섞여 있어도 된다
     * @return map 의 순회 순서와 무관하게 항상 같은 문자열
     */
    public static String of(Map<String, Object> features) {
        StringBuilder canonical = new StringBuilder();
        // 정렬해야 map 구현체·삽입 순서가 달라도 같은 키가 나온다.
        new TreeMap<>(features).forEach((name, value) -> canonical
                .append(name)
                .append('=')
                .append(value == null ? "" : value)
                .append(';'));
        return hash(canonical.toString());
    }

    private static String hash(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.substring(0, KEY_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 모든 JVM 이 반드시 제공한다. 여기 오면 런타임이 깨진 것이다.
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", e);
        }
    }
}
