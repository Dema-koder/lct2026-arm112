package ru.lct.arm112;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import ru.lct.arm112.service.DemoSeeder;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/** Проверяет, что демо-набор действительно наполняет API обеих страниц аналитики и не дублируется. */
@TestPropertySource(properties = "arm112.seed.analytics-demo-enabled=true")
class AnalyticsDemoDataIntegrationTest extends ApiTestSupport {
    @Autowired
    DemoSeeder seeder;

    @Test
    void exposesRichIdempotentAnalyticsDataset() throws Exception {
        String teacher = login("teacher", "teacher");
        String trainee = login("trainee", "trainee");

        JsonNode lessonsBefore = json(get("/api/v1/teacher/lessons", teacher));
        long demoBefore = countWithPrefix(lessonsBefore, "title", "Демо");
        assertThat(demoBefore).isBetween(12L, 13L);

        JsonNode results = json(get("/api/v1/trainee/results", trainee));
        long visibleDemoResults = countVisibleDemoResults(results);
        assertThat(visibleDemoResults).isEqualTo(10);

        JsonNode rating = json(get("/api/v1/trainee/rating", trainee));
        assertThat(rating.get("completedSessions").asInt()).isGreaterThanOrEqualTo(10);
        assertThat(rating.get("groupSize").asInt()).isEqualTo(5);
        assertThat(rating.get("rank").asInt()).isBetween(1, 5);

        String completedLessonId = null;
        for (JsonNode lesson : lessonsBefore) {
            if (lesson.get("title").asText().startsWith("Демо-аналитика:")
                    && lesson.get("state").asText().equals("COMPLETED")) {
                completedLessonId = lesson.get("id").asText();
                break;
            }
        }
        assertThat(completedLessonId).isNotNull();
        JsonNode report = json(get("/api/v1/teacher/lessons/" + completedLessonId + "/report", teacher));
        assertThat(report.get("rows").size()).isEqualTo(5);
        assertThat(report.get("rows").toString()).contains("\"aiTotal\"").contains("\"teacherTotal\"");

        seeder.seed();
        JsonNode lessonsAfter = json(get("/api/v1/teacher/lessons", teacher));
        assertThat(countWithPrefix(lessonsAfter, "title", "Демо")).isEqualTo(demoBefore);
        assertThat(countVisibleDemoResults(json(get("/api/v1/trainee/results", trainee))))
                .isEqualTo(visibleDemoResults);
    }

    private static long countWithPrefix(JsonNode items, String field, String prefix) {
        long count = 0;
        for (JsonNode item : items) if (item.get(field).asText().startsWith(prefix)) count++;
        return count;
    }

    private static long countVisibleDemoResults(JsonNode items) {
        long count = 0;
        for (JsonNode item : items) {
            if (item.get("visible").asBoolean()
                    && item.get("lessonTitle").asText().startsWith("Демо-аналитика:")) count++;
        }
        return count;
    }
}
