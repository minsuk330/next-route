package watoo.grd.nextroute.application.subway.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 시간순 정렬 후 인접 간격이 threshold를 초과하는 지점에서 subgroup으로 분할한다.
 * Phase A(OBSERVED_CODE_1)와 Phase C(INFERRED_FROM_PREV_DEPARTURE)가 동일 규칙으로
 * train number compression을 수행하도록 공용화한 유틸.
 */
public final class TimeGapSplitter {

    private TimeGapSplitter() {
    }

    /**
     * @param items     같은 그룹키로 묶인 항목들 (정렬 여부 무관)
     * @param timeFn    항목 → 비교 기준 시각
     * @param threshold 이 값을 초과하는 간격에서 분할 (예: 10분)
     * @return 시간 오름차순으로 분할된 subgroup 리스트 (빈 입력 → 빈 리스트)
     */
    public static <T> List<List<T>> splitByGap(List<T> items,
                                               Function<T, LocalDateTime> timeFn,
                                               Duration threshold) {
        List<List<T>> result = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return result;
        }

        List<T> sorted = items.stream()
                .sorted(Comparator.comparing(timeFn))
                .collect(Collectors.toList());

        List<T> current = new ArrayList<>();
        current.add(sorted.get(0));

        for (int i = 1; i < sorted.size(); i++) {
            Duration gap = Duration.between(timeFn.apply(sorted.get(i - 1)), timeFn.apply(sorted.get(i)));
            if (gap.compareTo(threshold) > 0) {
                result.add(current);
                current = new ArrayList<>();
            }
            current.add(sorted.get(i));
        }
        result.add(current);
        return result;
    }
}
