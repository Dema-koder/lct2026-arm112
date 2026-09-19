package ru.lct.arm112;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.lct.arm112.persistence.AuditRepository;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** RBAC трёх ролей, аудит, администрирование: пользователи, настройки, резервные копии. */
class RolesAndAdminIntegrationTest extends ApiTestSupport {
    @Autowired
    AuditRepository auditRepository;

    @Test
    void enforcesRolesAndWritesAudit() throws Exception {
        String admin = login("admin", "admin");
        String teacher = login("teacher", "teacher");
        String trainee = login("trainee", "trainee");

        assertThat(get("/api/v1/admin/users", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/admin/users", trainee).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/admin/users", teacher).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/teacher/lessons", trainee).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/teacher/lessons", admin).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/trainee/context", admin).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/admin/users", admin).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/teacher/lessons", teacher).statusCode()).isEqualTo(200);

        JsonNode me = json(get("/api/v1/auth/me", teacher));
        assertThat(me.get("role").asText()).isEqualTo("TEACHER");

        long before = auditRepository.count();
        HttpResponse<String> settings = put("/api/v1/admin/settings", "{\"audit.retention_days\":\"30\"}", admin);
        assertThat(settings.statusCode()).isEqualTo(422);   // ТЗ: не меньше 6 месяцев
        HttpResponse<String> ok = put("/api/v1/admin/settings", "{\"telephony.ringing_ms\":\"250\"}", admin);
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(json(ok).get("telephony.ringing_ms").asText()).isEqualTo("250");
        assertThat(auditRepository.count()).isGreaterThan(before);
        HttpResponse<String> audit = get("/api/v1/admin/audit?action=settings&limit=5", admin);
        assertThat(audit.statusCode()).isEqualTo(200);
        assertThat(json(audit).get("items").get(0).get("actorRole").asText()).isEqualTo("ADMIN");
        assertThat(json(audit).get("items").get(0).get("action").asText()).isEqualTo("PUT /api/v1/admin/settings");
    }

    @Test
    void managesUsersAndBackups() throws Exception {
        String admin = login("admin", "admin");
        String loginName = createTrainee(admin, "4");
        String token = login(loginName, "secret1");
        assertThat(json(get("/api/v1/trainee/context", token)).get("workstation").get("number").asText()).isEqualTo("4");

        JsonNode users = json(get("/api/v1/admin/users?role=TRAINEE", admin));
        String id = null;
        for (JsonNode u : users) if (u.get("login").asText().equals(loginName)) id = u.get("id").asText();
        assertThat(id).isNotNull();

        // пароль не должен попасть в аудит
        HttpResponse<String> audit = get("/api/v1/admin/audit?action=users&limit=5", admin);
        assertThat(audit.body()).doesNotContain("secret1");

        assertThat(post("/api/v1/admin/users/" + id + "/block", null, admin, null).statusCode()).isEqualTo(200);
        HttpResponse<String> blocked = post("/api/v1/auth/login",
                "{\"username\":\"" + loginName + "\",\"password\":\"secret1\"}", null, null);
        assertThat(blocked.statusCode()).isEqualTo(401);
        assertThat(post("/api/v1/admin/users/" + id + "/unblock", null, admin, null).statusCode()).isEqualTo(200);
        assertThat(post("/api/v1/admin/users/" + id + "/reset-password", "{\"password\":\"newpass1\"}", admin, null)
                .statusCode()).isEqualTo(204);
        login(loginName, "newpass1");

        HttpResponse<String> health = get("/api/v1/admin/system/health", admin);
        assertThat(json(health).get("database").asText()).isEqualTo("UP");

        HttpResponse<String> backup = post("/api/v1/admin/backups", null, admin, null);
        assertThat(backup.statusCode()).as(backup.body()).isEqualTo(201);
        String fileName = json(backup).get("fileName").asText();
        assertThat(json(get("/api/v1/admin/backups", admin)).toString()).contains(fileName);

        // изменяем данные после копии, восстанавливаем — изменение исчезает
        String afterBackup = createTrainee(admin, "5");
        assertThat(post("/api/v1/admin/backups/" + fileName + "/restore", "{\"confirm\":\"no\"}", admin, null).statusCode())
                .isEqualTo(422);
        HttpResponse<String> restored = post("/api/v1/admin/backups/" + fileName + "/restore",
                "{\"confirm\":\"RESTORE\"}", admin, null);
        assertThat(restored.statusCode()).as(restored.body()).isEqualTo(204);
        HttpResponse<String> gone = post("/api/v1/auth/login",
                "{\"username\":\"" + afterBackup + "\",\"password\":\"secret1\"}", null, null);
        assertThat(gone.statusCode()).isEqualTo(401);
        login(loginName, "newpass1");
    }
}
