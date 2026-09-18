package watoo.grd.nextroute.application.route.service;

import java.util.Map;

/** OTP GTFS {@code route.shortName} 기준의 수도권 도시철도 표시색. */
public final class SubwayLineColors {

    private static final Map<String, String> COLORS = Map.ofEntries(
            Map.entry("서울1호선", "#004A85"),
            Map.entry("서울2호선", "#00A23F"),
            Map.entry("서울3호선", "#ED6C00"),
            Map.entry("서울4호선", "#009BCE"),
            Map.entry("서울5호선", "#794698"),
            Map.entry("서울6호선", "#7C4932"),
            Map.entry("서울7호선", "#6E7E31"),
            Map.entry("서울8호선", "#D11D70"),
            Map.entry("서울9호선", "#A49D87"),
            Map.entry("인천1호선", "#B4C7E7"),
            Map.entry("인천2호선", "#F4A462"),
            Map.entry("공항철도", "#0079AC"),
            Map.entry("경춘선", "#007A62"),
            Map.entry("경의중앙선", "#6AC2B3"),
            Map.entry("신분당선", "#B81B30"),
            Map.entry("수인분당선", "#ECA300"),
            Map.entry("서해선", "#5EAC41"),
            Map.entry("GTX-A", "#AB087D"),
            Map.entry("김포도시철도", "#957326"),
            Map.entry("신림선", "#6789CA"),
            Map.entry("우이신설경전철", "#BACC50"),
            Map.entry("의정부경전철", "#F0831E"),
            Map.entry("용인경전철", "#44A436")
    );

    private SubwayLineColors() {
    }

    public static String colorFor(String routeShortName) {
        return COLORS.get(routeShortName);
    }
}
