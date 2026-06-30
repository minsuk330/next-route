package watoo.grd.nextroute.application.route.port.out;

import java.util.Optional;

/**
 * 현재 날씨 관측 조회(기상청 초단기실황). ML serving feature용.
 * <p>
 * 학습(build_dataset)은 ASOS 시간자료(T-1 관측)를 쓰고, 서빙은 실시간이 필요해
 * 초단기실황(getUltraSrtNcst)을 쓴다. 단위(℃/mm/%/m·s⁻¹)는 ASOS와 동일하게 맞춘다.
 * 적설(snow_depth_cm)은 실황에 없어 채우지 않는다(모델 feature에서 제외).
 */
public interface WeatherPort {

    Optional<WeatherFeatures> fetchCurrent();

    /**
     * dataset weather feature와 동일 이름/단위. null = 미측정/조회실패(serving이 NaN 처리).
     * is_raining/is_snowing은 초단기실황 PTY(강수형태)에서 파생 — 학습 쪽 dsnw/rn 파생과
     * 소스는 다르나 의미("그 시각 비/눈 여부")는 동일.
     */
    record WeatherFeatures(
            Double temperatureC,
            Double precipitationMm,
            Double humidityPct,
            Double windSpeedMps,
            Integer isRaining,
            Integer isSnowing) {
    }
}
