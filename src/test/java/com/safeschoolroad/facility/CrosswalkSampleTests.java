package com.safeschoolroad.facility;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class CrosswalkSampleTests {
    @Autowired CrosswalkSampleImporter importer;
    @Autowired JdbcTemplate jdbc;
    @Test void importsReviewedTenRecordsIdempotentlyAndConvertsCoordinates() throws Exception {
        assertEquals(10, importer.importSample());
        assertEquals(10, importer.importSample());
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM facility WHERE source=?", Integer.class,
                CrosswalkSampleImporter.SOURCE);
        assertEquals(10, count);
        Double lat = jdbc.queryForObject("SELECT MIN(lat) FROM facility WHERE source=?", Double.class,
                CrosswalkSampleImporter.SOURCE);
        Double lng = jdbc.queryForObject("SELECT MIN(lng) FROM facility WHERE source=?", Double.class,
                CrosswalkSampleImporter.SOURCE);
        assertTrue(lat > 37.4 && lat < 37.75);
        assertTrue(lng > 126.75 && lng < 127.25);
    }
}
