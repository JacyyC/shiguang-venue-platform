package com.example.venue.placement;

import org.springframework.test.annotation.DirtiesContext;

import com.example.venue.common.CurrentUser;
import com.example.venue.common.ResultCode;
import com.example.venue.placement.entity.PlacementCandidateVersion;
import com.example.venue.placement.entity.PlacementSlot;
import com.example.venue.placement.mapper.PlacementCandidateVersionMapper;
import com.example.venue.placement.mapper.PlacementSlotMapper;
import com.example.venue.placement.profile.PlacementMemberProfileReader;
import com.example.venue.placement.source.PlacementSourceReader;
import com.example.venue.placement.source.PlacementSourceReaderRegistry;
import com.example.venue.placement.source.PlacementSourceType;
import com.example.venue.security.JwtTokenService;
import com.example.venue.security.SecurityUserService;
import com.example.venue.user.entity.SysUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * S09 real HTTP fault boundary. The Reader is the only injected fault seam; the request still
 * traverses the actual Spring server, JWT filter, controller, service and exception handler.
 * No placement rows are written by this fixture.
 */
// Close the cached Spring context while this class still owns live containers.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "app.provider.observability.scheduled-enabled=false",
        "app.community.retention.enabled=false",
        "spring.task.scheduling.enabled=false",
        "logging.level.com.example.venue=ERROR"
    }
)
class PlacementSourceReaderFailureHttpIntegrationTest {
    private static final Long USER_ID = 1L;
    private static final Long SLOT_ID = 1L;
    private static final Long CANDIDATE_ID = 990901L;
    private static final Long SOURCE_ID = 990902L;
    private static final String INTERNAL_MARKER = "source-reader-secret";
    private static final CurrentUser OPERATOR = new CurrentUser(
        USER_ID,
        "s09-http-fault-user",
        Set.of("SYSTEM_ADMIN"),
        Set.of("placement:manage", "placement:publish", "placement:audit:view"));

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("placement_reader_http")
        .withUsername("placement")
        .withPassword("placement")
        .withCommand("--character-set-server=utf8mb4",
                     "--collation-server=utf8mb4_unicode_ci",
                     "--log-bin-trust-function-creators=1");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
        DockerImageName.parse("m.daocloud.io/docker.io/library/redis:7-alpine"))
        .withCommand("redis-server --appendonly no --save \"\"")
        .withExposedPorts(6379)
        .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PlacementSlotMapper slotMapper;

    @MockBean
    private PlacementCandidateVersionMapper candidateMapper;

    @MockBean
    private PlacementMemberProfileReader memberProfileReader;

    @MockBean
    private PlacementSourceReaderRegistry readerRegistry;

    @MockBean(name = "placementFailureReader")
    private PlacementSourceReader reader;

    @MockBean
    private SecurityUserService securityUserService;

    @BeforeEach
    void setUpFixture() {
        when(securityUserService.loadEnabledUser(USER_ID)).thenReturn(enabledUser());
        when(securityUserService.toCurrentUser(any(SysUser.class))).thenReturn(OPERATOR);
        when(readerRegistry.find(PlacementSourceType.GLOBAL_NOTICE)).thenReturn(Optional.of(reader));
        when(slotMapper.findByCode("HOME_PRIMARY")).thenReturn(enabledSlot());
        when(slotMapper.selectList(any())).thenReturn(List.of());
        when(candidateMapper.findPublishedForDecision(eq(SLOT_ID), any(), anyInt()))
            .thenReturn(List.of(publishedCandidate()));
        when(memberProfileReader.currentLevelCode(USER_ID)).thenReturn("BASIC");
    }

    static Stream<Arguments> readerFailures() {
        return Stream.of(
            Arguments.of("exception", new IllegalStateException(INTERNAL_MARKER + "-exception")),
            Arguments.of("timeout", new CompletionException(new TimeoutException(INTERNAL_MARKER + "-timeout"))),
            Arguments.of("5xx", new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_MARKER + "-5xx")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("readerFailures")
    void userDecisionContainsReaderFailureAndVenueEntryRemainsAvailable(
        String failureType,
        RuntimeException failure
    ) throws Exception {
        when(reader.readForUser(SOURCE_ID, USER_ID)).thenThrow(failure);

        ResponseEntity<String> decision = get("/api/placements/HOME_PRIMARY/decision");
        assertThat(decision.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode decisionBody = objectMapper.readTree(decision.getBody());
        assertThat(decisionBody.path("code").asInt()).isZero();
        assertThat(decisionBody.get("data")).isNull();
        assertThat(decision.getBody()).doesNotContain(INTERNAL_MARKER);

        ResponseEntity<String> venues = restTemplate.getForEntity(
            url("/api/venues?pageNum=1&pageSize=6"), String.class);
        assertThat(venues.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(venues.getBody()).doesNotContain(INTERNAL_MARKER);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("readerFailures")
    void operatorPreviewUsesStableFailureWithoutLeakingReaderCause(
        String failureType,
        RuntimeException failure
    ) throws Exception {
        when(reader.readForPreview(SOURCE_ID)).thenThrow(failure);

        ResponseEntity<String> preview = get(
            "/api/content-admin/placements/sources/preview?sourceType=GLOBAL_NOTICE&sourceId=" + SOURCE_ID);
        assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        JsonNode previewBody = objectMapper.readTree(preview.getBody());
        assertThat(previewBody.path("code").asInt()).isEqualTo(ResultCode.SYSTEM_ERROR.getCode());
        assertThat(previewBody.path("message").asText()).isEqualTo("来源暂时不可用，请稍后重试");
        assertThat(preview.getBody()).doesNotContain(INTERNAL_MARKER);

        ResponseEntity<String> slots = get("/api/content-admin/placements/slots");
        assertThat(slots.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(slots.getBody()).path("code").asInt()).isZero();
    }

    private ResponseEntity<String> get(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtTokenService.createToken(OPERATOR, 0L));
        return restTemplate.exchange(
            url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private SysUser enabledUser() {
        SysUser user = new SysUser();
        user.id = USER_ID;
        user.username = OPERATOR.username();
        user.status = "ACTIVE";
        user.credentialVersion = 0L;
        return user;
    }

    private PlacementSlot enabledSlot() {
        PlacementSlot slot = new PlacementSlot();
        slot.id = SLOT_ID;
        slot.slotCode = "HOME_PRIMARY";
        slot.status = "ENABLED";
        return slot;
    }

    private PlacementCandidateVersion publishedCandidate() {
        PlacementCandidateVersion candidate = new PlacementCandidateVersion();
        candidate.id = CANDIDATE_ID;
        candidate.slotId = SLOT_ID;
        candidate.sourceType = PlacementSourceType.GLOBAL_NOTICE.name();
        candidate.sourceId = SOURCE_ID;
        candidate.effectiveFrom = LocalDateTime.now().minusMinutes(1);
        candidate.effectiveUntil = LocalDateTime.now().plusMinutes(30);
        candidate.audienceType = "ALL_AUTHENTICATED";
        candidate.priority = 10;
        candidate.frequencyCapPerUser = 1;
        candidate.frequencyCapWindowHours = 24;
        candidate.ruleVersion = "PLACEMENT_RULE_V1";
        candidate.status = "PUBLISHED";
        return candidate;
    }
}
