package ru.lct.arm112.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MaterialRepository {
    private static final RowMapper<MaterialRow> MAPPER = (rs, row) -> new MaterialRow(
            rs.getObject("id", UUID.class), rs.getObject("teacher_id", UUID.class), rs.getString("title"),
            rs.getString("file_name"), rs.getString("content_type"), rs.getLong("size_bytes"),
            rs.getString("storage_path"), rs.getTimestamp("uploaded_at").toInstant());

    private final JdbcTemplate jdbc;

    public MaterialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<MaterialRow> findByTeacher(UUID teacherId) {
        return jdbc.query("select * from material where teacher_id = ? order by uploaded_at desc", MAPPER, teacherId);
    }

    public List<MaterialRow> findByGroup(UUID groupId) {
        return jdbc.query("""
                select m.* from material m join material_group mg on mg.material_id = m.id
                 where mg.group_id = ? order by m.uploaded_at desc
                """, MAPPER, groupId);
    }

    public Optional<MaterialRow> findById(UUID id) {
        return jdbc.query("select * from material where id = ?", MAPPER, id).stream().findFirst();
    }

    public List<UUID> groupsOf(UUID materialId) {
        return jdbc.query("select group_id from material_group where material_id = ?",
                (rs, row) -> rs.getObject("group_id", UUID.class), materialId);
    }

    @Transactional
    public void insert(MaterialRow row, List<UUID> groupIds) {
        jdbc.update("""
                insert into material (id, teacher_id, title, file_name, content_type, size_bytes, storage_path)
                values (?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.teacherId(), row.title(), row.fileName(), row.contentType(), row.sizeBytes(),
                row.storagePath());
        for (UUID groupId : groupIds) {
            jdbc.update("insert into material_group (material_id, group_id) values (?, ?)", row.id(), groupId);
        }
    }

    public void delete(UUID id) {
        jdbc.update("delete from material_group where material_id = ?", id);
        jdbc.update("delete from material where id = ?", id);
    }

    public record MaterialRow(UUID id, UUID teacherId, String title, String fileName, String contentType,
                              long sizeBytes, String storagePath, Instant uploadedAt) {}
}
