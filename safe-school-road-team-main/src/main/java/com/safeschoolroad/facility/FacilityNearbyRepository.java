package com.safeschoolroad.facility;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FacilityNearbyRepository {
    private final JdbcTemplate jdbc;

    public FacilityNearbyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record NearbyFacility(
            long id,
            String kind,
            String district,
            double lat,
            double lng,
            String source,
            String originalId,
            LocalDate sourceDownloaded) {
    }

    public List<NearbyFacility> findInBox(
            String district, List<String> kinds,
            double minLat, double minLng, double maxLat, double maxLng, int limit) {

        int safeLimit = Math.max(1, Math.min(500, limit));
        List<String> filters = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        filters.add("lat BETWEEN ? AND ?");
        filters.add("lng BETWEEN ? AND ?");
        params.add(minLat);
        params.add(maxLat);
        params.add(minLng);
        params.add(maxLng);

        if (district != null) {
            filters.add("district = ?");
            params.add(district);
        }

        if (kinds != null && !kinds.isEmpty()) {
            String placeholders = String.join(", ", Collections.nCopies(kinds.size(), "?"));
            filters.add("kind IN (" + placeholders + ")");
            params.addAll(kinds);
        }

        params.add(safeLimit);
        String sql = "SELECT id, kind, district, lat, lng, source, original_id, source_downloaded "
                + "FROM facility WHERE " + String.join(" AND ", filters)
                + " ORDER BY id LIMIT ?";

        return jdbc.query(sql, (rs, rowNum) -> new NearbyFacility(
                rs.getLong("id"),
                rs.getString("kind"),
                rs.getString("district"),
                rs.getDouble("lat"),
                rs.getDouble("lng"),
                rs.getString("source"),
                rs.getString("original_id"),
                rs.getDate("source_downloaded").toLocalDate()),
                params.toArray());
    }
}
