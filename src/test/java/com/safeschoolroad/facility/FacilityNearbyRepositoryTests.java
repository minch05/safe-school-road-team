package com.safeschoolroad.facility;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class FacilityNearbyRepositoryTests {
    @Autowired
    FacilityNearbyRepository repository;

    @Autowired
    CrosswalkSampleImporter importer;

    @Autowired
    JdbcTemplate jdbc;

    private double sampleLat;
    private double sampleLng;

    @BeforeEach
    void importSampleAndReadCoordinates() throws Exception {
        importer.importSample();
        List<double[]> samples = jdbc.query(
                "SELECT lat, lng FROM facility WHERE source = ? ORDER BY id LIMIT 1",
                (rs, rowNum) -> new double[] {rs.getDouble("lat"), rs.getDouble("lng")},
                CrosswalkSampleImporter.SOURCE);
        assertFalse(samples.isEmpty(), "개발용 횡단보도 표본이 적재되어야 합니다.");
        sampleLat = samples.get(0)[0];
        sampleLng = samples.get(0)[1];
    }

    private List<FacilityNearbyRepository.NearbyFacility> findAroundSample(String district) {
        return repository.findInBox(
                district, List.of("CROSSWALK"),
                sampleLat - 0.0001, sampleLng - 0.0001,
                sampleLat + 0.0001, sampleLng + 0.0001,
                100);
    }

    @Test
    void findsAtLeastOneFacilityNearSampleCoordinates() {
        List<FacilityNearbyRepository.NearbyFacility> result = findAroundSample("중랑구");
        assertFalse(result.isEmpty());
        assertTrue(result.stream().allMatch(f -> "CROSSWALK".equals(f.kind())));
    }

    @Test
    void findsNothingOutsideSeoul() {
        List<FacilityNearbyRepository.NearbyFacility> result = repository.findInBox(
                null, List.of(), 35.0, 128.0, 35.1, 128.1, 100);
        assertTrue(result.isEmpty());
    }

    @Test
    void findsNothingWithDifferentDistrict() {
        assertTrue(findAroundSample("마포구").isEmpty());
    }

    @Test
    void importingSameSampleTwiceDoesNotIncreaseResults() throws Exception {
        int before = findAroundSample("중랑구").size();
        importer.importSample();
        importer.importSample();
        int after = findAroundSample("중랑구").size();
        assertTrue(before > 0);
        assertEquals(before, after);
    }
}
