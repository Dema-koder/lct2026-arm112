package ru.lct.arm112;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import ru.lct.arm112.persistence.AuditRepository;
import ru.lct.arm112.service.PhoneCallAlertService;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** RBAC трёх ролей, аудит, администрирование: пользователи, настройки, резервные копии. */
class RolesAndAdminIntegrationTest extends ApiTestSupport {
    @Autowired
    AuditRepository auditRepository;
    @Autowired
    PhoneCallAlertService phoneCalls;

    @Value("${arm112.materials.dir}")
    String materialsDirectory;

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
    void managesRuntimeServicesAndKeepsInfrastructureReadOnly() throws Exception {
        String admin = login("admin", "admin");
        String teacher = login("teacher", "teacher");

        HttpResponse<String> services = get("/api/v1/admin/system/services", admin);
        assertThat(services.statusCode()).as(services.body()).isEqualTo(200);
        assertThat(services.body()).contains("\"id\":\"backend\"", "\"id\":\"database\"",
                "\"id\":\"simulation\"", "\"id\":\"telephony\"", "\"id\":\"realtime\"",
                "\"id\":\"jobs\"", "\"purpose\":", "\"stopEffect\":", "\"restartWhen\":",
                "\"lastCheckedAt\":", "\"responseTimeMs\":", "\"recommendedAction\":", "\"dependencies\":");
        assertThat(get("/api/v1/admin/system/services", teacher).statusCode()).isEqualTo(403);
        assertThat(post("/api/v1/admin/system/services/diagnostics", null, admin, null).statusCode()).isEqualTo(200);

        HttpResponse<String> stopped = post("/api/v1/admin/system/services/jobs/actions",
                "{\"action\":\"STOP\"}", admin, null);
        assertThat(stopped.statusCode()).as(stopped.body()).isEqualTo(200);
        assertThat(json(stopped).get("state").asText()).isEqualTo("STOPPED");
        assertThat(json(stopped).get("allowedActions").toString()).contains("START");

        HttpResponse<String> started = post("/api/v1/admin/system/services/jobs/actions",
                "{\"action\":\"START\"}", admin, null);
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        assertThat(json(started).get("state").asText()).isEqualTo("RUNNING");

        HttpResponse<String> history = get("/api/v1/admin/system/services/history?serviceId=jobs", admin);
        assertThat(history.statusCode()).as(history.body()).isEqualTo(200);
        assertThat(history.body()).contains("\"serviceId\":\"jobs\"", "\"actorLogin\":\"admin\"",
                "\"action\":\"START\"", "\"action\":\"STOP\"");
        assertThat(get("/api/v1/admin/system/services/history", teacher).statusCode()).isEqualTo(403);

        HttpResponse<String> alerts = get("/api/v1/admin/system/alerts", admin);
        assertThat(alerts.statusCode()).isEqualTo(200);
        assertThat(alerts.body()).contains("\"configured\":false", "\"channel\":\"PHONE_CALL\"");
        assertThat(post("/api/v1/admin/system/alerts/test", null, admin, null).statusCode()).isEqualTo(409);
        phoneCalls.sendDetailed("Проверка истории", "TEST", null);
        HttpResponse<String> callHistory = get("/api/v1/admin/system/alerts/history", admin);
        assertThat(callHistory.statusCode()).isEqualTo(200);
        assertThat(callHistory.body()).contains("Проверка истории", "\"status\":\"FAILED\"",
                "Телефонный шлюз не настроен");
        String attemptId = json(callHistory).get(0).get("id").asText();
        HttpResponse<String> repeated = post("/api/v1/admin/system/alerts/history/" + attemptId + "/retry",
                null, admin, null);
        assertThat(repeated.statusCode()).isEqualTo(409);
        assertThat(repeated.body()).contains("ALERTS_NOT_CONFIGURED");
        assertThat(get("/api/v1/admin/system/alerts/history", teacher).statusCode()).isEqualTo(403);

        HttpResponse<String> protectedService = post("/api/v1/admin/system/services/backend/actions",
                "{\"action\":\"STOP\"}", admin, null);
        assertThat(protectedService.statusCode()).isEqualTo(409);
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
        assertThat(get("/api/v1/trainee/context", token).statusCode())
                .as("старый JWT должен быть отозван сразу после блокировки")
                .isEqualTo(401);
        HttpResponse<String> blocked = post("/api/v1/auth/login",
                "{\"username\":\"" + loginName + "\",\"password\":\"secret1\"}", null, null);
        assertThat(blocked.statusCode()).isEqualTo(401);
        assertThat(post("/api/v1/admin/users/" + id + "/unblock", null, admin, null).statusCode()).isEqualTo(200);
        assertThat(post("/api/v1/admin/users/" + id + "/reset-password", "{\"password\":\"newpass1\"}", admin, null)
                .statusCode()).isEqualTo(204);
        login(loginName, "newpass1");

        HttpResponse<String> health = get("/api/v1/admin/system/health", admin);
        assertThat(json(health).get("database").asText()).isEqualTo("UP");

        // группу нельзя перепривязать к пользователю без роли TEACHER
        String teacher = login("teacher", "teacher");
        HttpResponse<String> calibration = get("/api/v1/admin/assessment-calibration?mode=CARD_FILL", admin);
        assertThat(calibration.statusCode()).isEqualTo(200);
        assertThat(json(calibration).get("candidate").get("mode").asText()).isEqualTo("CARD_FILL");
        assertThat(get("/api/v1/admin/assessment-calibration?mode=CARD_FILL", teacher).statusCode()).isEqualTo(403);
        assertThat(post("/api/v1/admin/assessment-calibration/activate",
                "{\"mode\":\"CARD_FILL\"}", admin, null).statusCode()).isEqualTo(409);
        HttpResponse<String> group = post("/api/v1/admin/groups",
                "{\"name\":\"Проверка ролей\",\"teacherId\":\"" + userId(teacher) + "\"}", admin, null);
        assertThat(group.statusCode()).as(group.body()).isEqualTo(201);
        String ownGroupId = json(group).get("id").asText();
        HttpResponse<String> members = put("/api/v1/admin/groups/" + ownGroupId + "/members",
                "{\"memberIds\":[\"" + id + "\"]}", admin);
        assertThat(members.statusCode()).as(members.body()).isEqualTo(200);
        assertThat(members.body()).contains(loginName, "\"groupId\":\"" + ownGroupId + "\"");
        assertThat(put("/api/v1/admin/groups/" + ownGroupId + "/members",
                "{\"memberIds\":[]}", teacher).statusCode()).isEqualTo(403);
        assertThat(put("/api/v1/admin/groups/" + json(group).get("id").asText(),
                "{\"name\":\"Проверка ролей\",\"teacherId\":\"" + id + "\"}", admin).statusCode())
                .isEqualTo(422);

        String secondTeacherLogin = "teacher-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> secondTeacher = post("/api/v1/admin/users",
                "{\"login\":\"" + secondTeacherLogin + "\",\"password\":\"teacher2\","
                        + "\"displayName\":\"Второй преподаватель\",\"role\":\"TEACHER\"}", admin, null);
        assertThat(secondTeacher.statusCode()).as(secondTeacher.body()).isEqualTo(201);
        HttpResponse<String> foreignGroup = post("/api/v1/admin/groups",
                "{\"name\":\"Чужая группа\",\"teacherId\":\"" + json(secondTeacher).get("id").asText() + "\"}",
                admin, null);
        assertThat(foreignGroup.statusCode()).as(foreignGroup.body()).isEqualTo(201);
        assertThat(uploadMaterial(teacher, "Чужой материал", json(foreignGroup).get("id").asText()).statusCode())
                .isEqualTo(403);
        assertThat(uploadMaterial(teacher, "Памятка", ownGroupId).statusCode()).isEqualTo(201);

        Path material = Path.of(materialsDirectory).resolve("backup-" + UUID.randomUUID() + ".txt");
        Files.createDirectories(material.getParent());
        Files.writeString(material, "содержимое до резервной копии");

        HttpResponse<String> backup = post("/api/v1/admin/backups", null, admin, null);
        assertThat(backup.statusCode()).as(backup.body()).isEqualTo(201);
        String fileName = json(backup).get("fileName").asText();
        assertThat(json(get("/api/v1/admin/backups", admin)).toString()).contains(fileName);

        // изменяем данные после копии, восстанавливаем — изменение исчезает
        String afterBackup = createTrainee(admin, "5");
        Files.writeString(material, "испорчено после резервной копии");
        assertThat(post("/api/v1/admin/backups/" + fileName + "/restore", "{\"confirm\":\"no\"}", admin, null).statusCode())
                .isEqualTo(422);
        HttpResponse<String> restored = post("/api/v1/admin/backups/" + fileName + "/restore",
                "{\"confirm\":\"RESTORE\"}", admin, null);
        assertThat(restored.statusCode()).as(restored.body()).isEqualTo(204);
        HttpResponse<String> gone = post("/api/v1/auth/login",
                "{\"username\":\"" + afterBackup + "\",\"password\":\"secret1\"}", null, null);
        assertThat(gone.statusCode()).isEqualTo(401);
        login(loginName, "newpass1");
        assertThat(Files.readString(material)).isEqualTo("содержимое до резервной копии");
    }
}
