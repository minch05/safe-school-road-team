package com.safeschoolroad.facility;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:coordinate_verify;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
class DistrictCoordinateVerificationTests {

	@Autowired
	SupportedDataImporter importer;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void verifyCoordinates() throws Exception {
		importer.importAll();

		check("중랑구", "06-0000019003",
				37.5816326, 127.0799577);

		check("강서구", "06-0000019294",
				37.5735085, 126.816735);
	}

	private void check(String district, String originalId,
					   double expectedLat, double expectedLng) {

		var rows = jdbc.queryForList("""
            SELECT district, kind, lat, lng, source, original_id
            FROM facility
            WHERE district = ? AND original_id = ?
            """, district, originalId);

		assertEquals(1, rows.size());

		var row = rows.get(0);

		System.out.println("검증 결과: " + row);

		assertEquals("CROSSWALK", row.get("kind"));
		assertEquals("서울시 교통안전시설물 횡단보도 OA-15554",
				row.get("source"));
		assertEquals(originalId, row.get("original_id"));

		assertEquals(expectedLat,
				((Number) row.get("lat")).doubleValue(), 0.0000001);

		assertEquals(expectedLng,
				((Number) row.get("lng")).doubleValue(), 0.0000001);
	}
}
