package watoo.grd.nextroute.application.route.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WalkCacheKeyTest {

    @Test
    void TC_같은_입력은_같은_키() {
        WalkCacheKey k1 = new WalkCacheKey(127.0276, 37.4979, 127.0285, 37.4985, 0);
        WalkCacheKey k2 = new WalkCacheKey(127.0276, 37.4979, 127.0285, 37.4985, 0);
        assertThat(k1.asKey()).isEqualTo(k2.asKey());
    }

    @Test
    void TC_좌표가_라운딩_정밀도_안에서_같으면_같은_키() {
        // 1e-4 정밀도 안에서의 차이는 같은 키로 정규화
        WalkCacheKey k1 = new WalkCacheKey(127.02761, 37.49791, 127.02851, 37.49851, 0);
        WalkCacheKey k2 = new WalkCacheKey(127.02764, 37.49794, 127.02854, 37.49854, 0);
        assertThat(k1.asKey()).isEqualTo(k2.asKey());
    }

    @Test
    void TC_searchOption_다르면_키_다름() {
        WalkCacheKey k1 = new WalkCacheKey(127.0276, 37.4979, 127.0285, 37.4985, 0);
        WalkCacheKey k2 = new WalkCacheKey(127.0276, 37.4979, 127.0285, 37.4985, 10);
        assertThat(k1.asKey()).isNotEqualTo(k2.asKey());
    }

    @Test
    void TC_방향_바뀌면_다른_키() {
        // start ↔ end swap 시 다른 캐시 항목
        WalkCacheKey forward = new WalkCacheKey(127.0276, 37.4979, 127.0285, 37.4985, 0);
        WalkCacheKey reverse = new WalkCacheKey(127.0285, 37.4985, 127.0276, 37.4979, 0);
        assertThat(forward.asKey()).isNotEqualTo(reverse.asKey());
    }

    @Test
    void TC_키_포맷_검증() {
        WalkCacheKey k = new WalkCacheKey(127.0276, 37.4979, 127.0285, 37.4985, 0);
        assertThat(k.asKey()).isEqualTo("coord:127.0276:37.4979|coord:127.0285:37.4985|opt:0");
    }
}
