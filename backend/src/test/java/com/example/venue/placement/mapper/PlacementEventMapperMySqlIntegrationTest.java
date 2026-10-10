package com.example.venue.placement.mapper;

import org.springframework.test.annotation.DirtiesContext;

import com.example.venue.placement.entity.PlacementEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Close the cached Spring context while this class still owns live containers.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(properties = {
    "spring.profiles.active=test",
    "spring.task.scheduling.enabled=false",
    "app.provider.observability.scheduled-enabled=false",
    "app.community.retention.enabled=false",
    "spring.data.redis.host=127.0.0.1",
    "spring.data.redis.port=6399"
})
class PlacementEventMapperMySqlIntegrationTest {
    private static final String FIXTURE_PREFIX = "S09-MAPPER-";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("placement_event_mapper")
        .withUsername("placement")
        .withPassword("placement")
        .withCommand("--character-set-server=utf8mb4",
                     "--collation-server=utf8mb4_unicode_ci",
                     "--log-bin-trust-function-creators=1");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired
    private PlacementEventMapper eventMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void deleteFixtures() {
        jdbc.update("DELETE FROM placement_event WHERE event_no LIKE ?", FIXTURE_PREFIX + "%");
    }

    @Test
    void duplicateKeyIsTranslatedAndOnlyKnownConflictNeedsServiceHandling() {
        PlacementEvent first = event("DUPLICATE-1");
        eventMapper.insertEvent(first);

        PlacementEvent duplicate = event("DUPLICATE-2");
        duplicate.eventKey = first.eventKey;

        assertThatThrownBy(() -> eventMapper.insertEvent(duplicate))
            .isInstanceOf(DuplicateKeyException.class);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM placement_event WHERE event_key = ?", Integer.class, first.eventKey))
            .isEqualTo(1);
    }

    @Test
    void checkConstraintFailureRemainsVisible() {
        PlacementEvent invalid = event("CHECK-1");
        invalid.eventType = "INVALID_EVENT";

        assertThatThrownBy(() -> eventMapper.insertEvent(invalid))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("chk_placement_event_type");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM placement_event WHERE event_no = ?", Integer.class, invalid.eventNo))
            .isZero();
    }

    @Test
    void maxLengthEventKeyIsPersistedWithoutTruncation() {
        PlacementEvent event = event("LENGTH-160");
        event.eventKey = "x".repeat(160);

        assertThat(eventMapper.insertEvent(event)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT CHAR_LENGTH(event_key) FROM placement_event WHERE event_no = ?", Integer.class,
            event.eventNo)).isEqualTo(160);
    }

    @Test
    void oversizedEventKeyRemainsVisibleInsteadOfBeingTruncated() {
        PlacementEvent invalid = event("LENGTH-1");
        invalid.eventKey = "x".repeat(161);

        assertThatThrownBy(() -> eventMapper.insertEvent(invalid))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("event_key");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM placement_event WHERE event_no = ?", Integer.class, invalid.eventNo))
            .isZero();
    }

    private PlacementEvent event(String suffix) {
        LocalDateTime now = LocalDateTime.now();
        PlacementEvent event = new PlacementEvent();
        event.eventNo = FIXTURE_PREFIX + suffix;
        event.eventKey = FIXTURE_PREFIX + "KEY-" + suffix;
        event.eventType = "CLICK";
        event.decisionId = 970001L;
        event.userId = 970001L;
        event.slotId = 970001L;
        event.candidateVersionId = 970001L;
        event.requestHash = "a".repeat(64);
        event.resultStatus = "ACCEPTED";
        event.occurredAt = now;
        event.createTime = now;
        return event;
    }
}
