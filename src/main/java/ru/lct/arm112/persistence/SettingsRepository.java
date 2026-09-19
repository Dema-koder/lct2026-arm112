package ru.lct.arm112.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Repository
public class SettingsRepository {
    private final JdbcTemplate jdbc;

    public SettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, String> loadAll() {
        Map<String, String> result = new LinkedHashMap<>();
        jdbc.query("select setting_key, setting_value from app_setting order by setting_key",
                rs -> { result.put(rs.getString("setting_key"), rs.getString("setting_value")); });
        return result;
    }

    public void upsert(String key, String value, UUID updatedBy) {
        int updated = jdbc.update(
                "update app_setting set setting_value = ?, updated_by = ?, updated_at = current_timestamp where setting_key = ?",
                value, updatedBy, key);
        if (updated == 0) {
            jdbc.update("insert into app_setting (setting_key, setting_value, updated_by) values (?, ?, ?)",
                    key, value, updatedBy);
        }
    }
}
