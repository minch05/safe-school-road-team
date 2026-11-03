package com.safeschoolroad.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;

class RouteContractTests {
    private static final String TMAP_SAMPLE = """
      {"features":[
        {"geometry":{"type":"Point","coordinates":[127.087,37.590]},"properties":{"totalDistance":850,"totalTime":650}},
        {"geometry":{"type":"LineString","coordinates":[[127.087,37.590],[127.089,37.592],[127.092,37.594]]},"properties":{}}
      ]}
      """;
    @Test void parsesCoordinateOrderAndRejectsBadResponse() {
        var gateway = new TmapGateway(new ObjectMapper(), "test-key", 950);
        var route = gateway.parse(TMAP_SAMPLE, 0);
        assertEquals(3, route.line().size());
        assertEquals(37.590, route.line().get(0).lat());
        assertEquals(127.087, route.line().get(0).lng());
        assertEquals(850, route.distanceM());
        assertThrows(TmapGateway.ProviderUnavailable.class, () -> gateway.parse("{\"features\":[]}", 0));
    }
    @Test void sameGeometryMergesOptionsWithoutInventingSafetyScore() {
        var fake = new TmapGateway(new ObjectMapper(), "test-key", 950) {
            @Override public RouteOption walking(Point start, Point end, int option) {
                return parse(TMAP_SAMPLE, option);
            }
        };
        var response = new RouteApi(fake).candidates(new RouteApi.Request(new Point(37.590,127.087), new Point(37.594,127.092)));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = (java.util.Map<?,?>) response.getBody();
        var candidates = (List<?>) body.get("candidates");
        assertEquals(1, candidates.size());
        assertEquals(List.of(0,4,10), ((RouteApi.Candidate) candidates.get(0)).options());
        assertEquals("제공하지 않음", body.get("safetyScore"));
    }
    @Test void invalidCoordinatesAndMissingKeyDoNotReturnFakePaths() {
        var gateway = new TmapGateway(new ObjectMapper(), "", 950);
        var api = new RouteApi(gateway);
        assertEquals(HttpStatus.BAD_REQUEST, api.candidates(new RouteApi.Request(new Point(0,0),new Point(37.59,127.08))).getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, api.candidates(new RouteApi.Request(new Point(37.59,127.08),new Point(37.6,127.09))).getStatusCode());
    }
}
