package com.example.venue.notice;

import org.springframework.test.annotation.DirtiesContext;

import com.example.venue.notice.email.EmailIdempotencyKeyFactory;
import com.example.venue.notice.email.EmailRecipientEligibility;
import com.example.venue.notice.email.EmailRecipientResolver;
import com.example.venue.notice.email.EmailSnapshotService;
import com.example.venue.notice.email.EmailTemplateRegistry;
import com.example.venue.notice.entity.NoticeMessage;
import com.example.venue.notice.mapper.NoticeEmailDeliveryMapper;
import com.example.venue.notice.mapper.NoticeMapper;
import com.example.venue.notice.mapper.NoticeMessageMapper;
import com.example.venue.notice.mapper.NoticeOutboxMapper;
import com.example.venue.notice.routing.DefaultNotificationRoutingPolicy;
import com.example.venue.notice.routing.NotificationEvent;
import com.example.venue.notice.routing.NotificationRecipientKind;
import com.example.venue.notice.service.NoticeDeliveryService;
import com.example.venue.notice.service.NoticeReceiptService;
import com.example.venue.notice.service.NoticeService;
import com.example.venue.venue.service.VenueAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// Close the cached Spring context while this class still owns live containers.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(
    classes = P2BusinessNotificationConcurrencyMySqlIntegrationTest.IntegrationApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.task.scheduling.enabled=false"
)
class P2BusinessNotificationConcurrencyMySqlIntegrationTest {
    private static final String INITIAL_LOOKUP_STATEMENT =
        NoticeOutboxMapper.class.getName() + ".findByIdempotency";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("p2_notification_concurrency")
        .withUsername("venue")
        .withPassword("venue")
        .withCommand("--character-set-server=utf8mb4",
                     "--collation-server=utf8mb4_unicode_ci",
                     "--log-bin-trust-function-creators=1");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @Autowired
    private NoticeService noticeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private InitialLookupBarrier lookupBarrier;

    private String fixturePrefix;

    @AfterEach
    void removeOnlyThisTestsFixtureRows() {
        if (fixturePrefix == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM notice_email_delivery WHERE outbox_id IN "
            + "(SELECT id FROM notice_outbox WHERE event_key = ?)", fixturePrefix);
        jdbcTemplate.update("DELETE FROM notice_outbox WHERE event_key = ?", fixturePrefix);
        jdbcTemplate.update("DELETE FROM notice_message WHERE business_id = ?", fixtureBusinessId());
        jdbcTemplate.update("DELETE FROM sys_config WHERE config_key LIKE ?", fixturePrefix + "%");
    }

    @Test
    void concurrentReplayKeepsOneImmutableRouteAndDoesNotRollbackEitherCaller() throws Exception {
        fixturePrefix = "P2_CONCURRENT:" + UUID.randomUUID().toString().replace("-", "");
        lookupBarrier.arm(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<NoticeMessage> first = executor.submit(() -> invokeFromCaller("one"));
            Future<NoticeMessage> second = executor.submit(() -> invokeFromCaller("two"));

            NoticeMessage firstResult = first.get(30, TimeUnit.SECONDS);
            NoticeMessage secondResult = second.get(30, TimeUnit.SECONDS);
            assertThat(firstResult.id).isEqualTo(secondResult.id);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(count("sys_config", "config_key LIKE ?", fixturePrefix + "%")).isEqualTo(2);
        assertThat(count("notice_message", "business_id = ?", fixtureBusinessId())).isEqualTo(1);
        assertThat(count("notice_outbox", "event_key = ? AND channel = 'IN_APP'", fixturePrefix))
            .isEqualTo(1);
        assertThat(count("notice_outbox", "event_key = ? AND channel = 'EMAIL'", fixturePrefix))
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notice_email_delivery d JOIN notice_outbox o ON o.id = d.outbox_id "
                + "WHERE o.event_key = ?",
            Long.class, fixturePrefix)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(DISTINCT d.message_id_header) FROM notice_email_delivery d "
                + "JOIN notice_outbox o ON o.id = d.outbox_id WHERE o.event_key = ?",
            Long.class, fixturePrefix)).isEqualTo(1L);
    }

    private NoticeMessage invokeFromCaller(String caller) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO sys_config(config_key, config_value, config_name) VALUES (?, ?, ?)",
                fixturePrefix + ":" + caller, "committed", "P2 concurrency caller");
            return noticeService.createBusinessNotification(event());
        });
    }

    private NotificationEvent event() {
        return new NotificationEvent(
            "RESERVATION_APPROVED", fixturePrefix, 7L, NotificationRecipientKind.USER,
            "预约审核结果", "预约已审核通过", "RESERVATION", fixtureBusinessId(),
            Map.of("reservationId", fixtureBusinessId().toString()));
    }

    private long count(String table, String predicate, Object parameter) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE " + predicate,
            Long.class, parameter);
    }

    private Long fixtureBusinessId() {
        return fixturePrefix == null ? -1L : (long) fixturePrefix.hashCode() & 0x7fff_ffffL;
    }

    static final class InitialLookupBarrier {
        private final AtomicReference<CountDownLatch> armed = new AtomicReference<>();

        void arm(int parties) {
            if (!armed.compareAndSet(null, new CountDownLatch(parties))) {
                throw new IllegalStateException("initial lookup barrier is already armed");
            }
        }

        void awaitIfArmed(String statementId, Object result) {
            CountDownLatch barrier = armed.get();
            if (barrier == null || !INITIAL_LOOKUP_STATEMENT.equals(statementId)
                || !(result instanceof List<?> rows) || !rows.isEmpty()) {
                return;
            }
            barrier.countDown();
            try {
                if (!barrier.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("concurrent initial route lookups did not rendezvous");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("concurrent lookup barrier was interrupted", exception);
            } finally {
                if (barrier.getCount() == 0) {
                    armed.compareAndSet(barrier, null);
                }
            }
        }
    }

    @Intercepts(@Signature(type = Executor.class, method = "query", args = {
        MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class
    }))
    static final class InitialLookupBarrierInterceptor implements Interceptor {
        private final InitialLookupBarrier barrier;

        InitialLookupBarrierInterceptor(InitialLookupBarrier barrier) {
            this.barrier = barrier;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            barrier.awaitIfArmed(statement.getId(), result);
            return result;
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @MapperScan(basePackageClasses = NoticeOutboxMapper.class)
    static class IntegrationApplication {
        @Bean
        InitialLookupBarrier initialLookupBarrier() {
            return new InitialLookupBarrier();
        }

        @Bean
        Interceptor initialLookupBarrierInterceptor(InitialLookupBarrier barrier) {
            return new InitialLookupBarrierInterceptor(barrier);
        }

        @Bean
        EmailTemplateRegistry emailTemplateRegistry() {
            return new EmailTemplateRegistry();
        }

        @Bean
        EmailSnapshotService emailSnapshotService(NoticeEmailDeliveryMapper deliveryMapper) {
            return new EmailSnapshotService(deliveryMapper);
        }

        @Bean
        NoticeService noticeService(
            NoticeMapper noticeMapper,
            NoticeMessageMapper messageMapper,
            NoticeOutboxMapper outboxMapper,
            NoticeEmailDeliveryMapper deliveryMapper,
            EmailTemplateRegistry registry
        ) {
            EmailRecipientResolver recipientResolver = (userId, recipientKind) ->
                new EmailRecipientEligibility(
                    userId, recipientKind, true, true,
                    "alice@example.test", "a***e@e***e.test", true, null);
            return new NoticeService(
                noticeMapper, messageMapper, outboxMapper, mock(VenueAccessService.class),
                mock(NoticeDeliveryService.class), mock(NoticeReceiptService.class),
                recipientResolver, new DefaultNotificationRoutingPolicy(true, registry, registry),
                registry, registry, new EmailIdempotencyKeyFactory(),
                new EmailSnapshotService(deliveryMapper), new ObjectMapper());
        }
    }
}
