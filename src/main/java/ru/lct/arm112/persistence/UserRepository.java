package ru.lct.arm112.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import ru.lct.arm112.security.Role;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {
    private static final RowMapper<AppUser> MAPPER = (rs, row) -> new AppUser(
            rs.getObject("id", UUID.class), rs.getString("login"), rs.getString("password_hash"),
            rs.getString("display_name"), Role.parse(rs.getString("role")),
            rs.getString("workstation_number"), rs.getObject("group_id", UUID.class),
            rs.getBoolean("active"), rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long count() {
        Long value = jdbc.queryForObject("select count(*) from app_user", Long.class);
        return value == null ? 0 : value;
    }

    public Optional<AppUser> findByLogin(String login) {
        return jdbc.query("select * from app_user where login = ?", MAPPER, login).stream().findFirst();
    }

    public Optional<AppUser> findById(UUID id) {
        return jdbc.query("select * from app_user where id = ?", MAPPER, id).stream().findFirst();
    }

    public List<AppUser> findAll(Role role, Boolean active) {
        StringBuilder sql = new StringBuilder("select * from app_user where 1=1");
        List<Object> args = new ArrayList<>();
        if (role != null) { sql.append(" and role = ?"); args.add(role.name()); }
        if (active != null) { sql.append(" and active = ?"); args.add(active); }
        sql.append(" order by role, display_name");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public List<AppUser> findByIds(List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        return jdbc.query("select * from app_user where id in (" + placeholders + ") order by display_name",
                MAPPER, ids.toArray());
    }

    public List<AppUser> findByGroup(UUID groupId) {
        return jdbc.query("select * from app_user where group_id = ? order by display_name", MAPPER, groupId);
    }

    public void insert(AppUser user, UUID createdBy) {
        jdbc.update("""
                insert into app_user (id, login, password_hash, display_name, role, workstation_number,
                                      group_id, active, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, user.id(), user.login(), user.passwordHash(), user.displayName(), user.role().name(),
                user.workstationNumber(), user.groupId(), user.active(), createdBy);
    }

    public void update(UUID id, String displayName, Role role, String workstationNumber, UUID groupId) {
        jdbc.update("""
                update app_user set display_name = ?, role = ?, workstation_number = ?, group_id = ?,
                                    updated_at = current_timestamp
                 where id = ?
                """, displayName, role.name(), workstationNumber, groupId, id);
    }

    public void setActive(UUID id, boolean active) {
        jdbc.update("update app_user set active = ?, updated_at = current_timestamp where id = ?", active, id);
    }

    public void setPasswordHash(UUID id, String hash) {
        jdbc.update("update app_user set password_hash = ?, updated_at = current_timestamp where id = ?", hash, id);
    }

    public record AppUser(UUID id, String login, String passwordHash, String displayName, Role role,
                          String workstationNumber, UUID groupId, boolean active, Instant createdAt) {
        public static AppUser create(String login, String hash, String displayName, Role role,
                                     String workstationNumber, UUID groupId) {
            return new AppUser(UUID.randomUUID(), login, hash, displayName, role, workstationNumber,
                    groupId, true, Instant.now());
        }
    }
}
