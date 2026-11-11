package com.safeschoolroad.place;

import com.safeschoolroad.route.Point;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SavedPlaceStore {
    private final JdbcTemplate jdbc;

    public SavedPlaceStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Place(long id, String name, double lat, double lng) { }

    public List<Place> list(long memberId) {
        return jdbc.query("SELECT id, name, lat, lng FROM saved_place WHERE member_id=? ORDER BY name, id",
                (rs, row) -> new Place(rs.getLong(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4)), memberId);
    }

    @Transactional
    public void save(long memberId, String name, Point point) {
        jdbc.queryForObject("SELECT id FROM member WHERE id=? FOR UPDATE", Long.class, memberId);
        var ids = jdbc.queryForList("SELECT id FROM saved_place WHERE member_id=? AND name=?", Long.class, memberId, name);
        if (ids.isEmpty()) {
            jdbc.update("INSERT INTO saved_place(member_id, name, lat, lng) VALUES (?,?,?,?)",
                    memberId, name, point.lat(), point.lng());
        } else {
            jdbc.update("UPDATE saved_place SET lat=?, lng=? WHERE id=?", point.lat(), point.lng(), ids.getFirst());
        }
    }

    public boolean delete(long memberId, long id) {
        return jdbc.update("DELETE FROM saved_place WHERE id=? AND member_id=?", id, memberId) == 1;
    }
}
