package watoo.grd.nextroute.infrastructure.adapter.out.api.tmap.dto;

/**
 * TMAP 보행자 경로 안내 요청 body.
 * 좌표는 (x, y) = (lng, lat) 순서. UTF-8 인코딩은 RestClient JSON 직렬화가 처리.
 */
public record TmapPedestrianRequest(
        String startX,
        String startY,
        String endX,
        String endY,
        String startName,
        String endName,
        String reqCoordType,
        String resCoordType,
        String searchOption,
        String sort
) {
    public static TmapPedestrianRequest of(
            double startX, double startY,
            double endX, double endY,
            String startName, String endName,
            int searchOption) {
        return new TmapPedestrianRequest(
                String.valueOf(startX),
                String.valueOf(startY),
                String.valueOf(endX),
                String.valueOf(endY),
                startName,
                endName,
                "WGS84GEO",
                "WGS84GEO",
                String.valueOf(searchOption),
                "index"
        );
    }
}
