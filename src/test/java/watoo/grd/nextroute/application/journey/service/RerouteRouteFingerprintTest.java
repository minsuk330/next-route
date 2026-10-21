package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.subway;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.subwayLane;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.walk;

/** 재탐색 후보 지문(#264) — 열차 종류(subway-express S2)는 일반이 아닐 때만 덧붙인다. */
class RerouteRouteFingerprintTest {

    private static PathResult path(SubPathResult rail) {
        return new PathResult(1, null, List.of(
                walk(3).names("출발", "가양").distance(100.0).build(), rail), List.of());
    }

    private static SubPathFixture.Builder line9() {
        return subway(4).names("가양", "염창").lanes(subwayLane("서울9호선", 9));
    }

    @Test
    @DisplayName("일반·모름(null)은 지문이 같다 — 배포 전 후보의 candidateId 가 그대로다")
    void normalAndUnknownKeepLegacyHash() {
        String unknown = RerouteRouteFingerprint.of("slot", path(line9().build()));
        String normal = RerouteRouteFingerprint.of("slot", path(line9().trainKind("NORMAL").build()));

        assertThat(normal).isEqualTo(unknown);
        // 배포 전 계산과 같은 값: sha1("slot|1:9:가양>염창") 앞 12바이트(열차 종류 칸이 없던 때의 키)
        assertThat(unknown).isEqualTo("7278f6c4285336a73392b142");
    }

    @Test
    @DisplayName("급행은 같은 노선·같은 역이어도 다른 경로다")
    void expressDiffers() {
        String normal = RerouteRouteFingerprint.of("slot", path(line9().trainKind("NORMAL").build()));
        String express = RerouteRouteFingerprint.of("slot", path(line9().trainKind("EXPRESS").build()));

        assertThat(express).isNotEqualTo(normal);
        assertThat(express).hasSize(24);
    }
}
