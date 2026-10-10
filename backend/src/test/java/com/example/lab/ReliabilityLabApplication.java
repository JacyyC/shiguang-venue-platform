package com.example.lab;

import com.example.venue.VenueReservationApplication;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test classpath only. Never packaged in the application jar. */
public class ReliabilityLabApplication {
    public static void main(String[] args) {
        if (!"reliability-lab".equals(System.getenv("SPRING_PROFILES_ACTIVE"))
            || System.getenv("LAB_SECRET") == null || !"127.0.0.1".equals(System.getenv("SERVER_ADDRESS"))) {
            throw new IllegalStateException("Lab requires explicit profile, secret and loopback binding");
        }
        SpringApplication.run(new Class<?>[]{VenueReservationApplication.class, LabConfig.class}, args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Profile("reliability-lab")
    @Import({Barrier.class, Control.class})
    public static class LabConfig {
        @Bean @Order(0)
        SecurityFilterChain labSecurity(HttpSecurity http) throws Exception {
            return http.securityMatcher("/__lab/**").csrf(c -> c.disable())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
        }
    }

    @Aspect
    public static class Barrier {
        private volatile String key = "";
        private volatile boolean rollback;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private volatile CountDownLatch release = new CountDownLatch(0);
        private volatile Map<String, Object> observation = Map.of("arrived", false);
        private final JdbcTemplate jdbc;
        public Barrier(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        synchronized void arm(String key, boolean rollback) {
            if (release.getCount() > 0) throw new IllegalStateException("barrier already armed");
            this.key = key; this.rollback = rollback; claimed.set(false);
            observation = Map.of("arrived", false); release = new CountDownLatch(1);
        }
        @Around("execution(* com.example.venue.reservation.service.ReservationCreateIdempotencyService.claim(..))")
        public Object claim(ProceedingJoinPoint point) throws Throwable {
            Object result = point.proceed();
            if (key.equals(point.getArgs()[1]) && claimed.compareAndSet(false, true)) {
                boolean active = TransactionSynchronizationManager.isActualTransactionActive();
                int visible = jdbc.queryForObject("select count(*) from reservation_create_command where user_id=? and idempotency_key=?",
                    Integer.class, point.getArgs()[0], key);
                observation = Map.of("arrived", true, "transactionActive", active, "ownConnectionRows", visible);
                if (!active || visible != 1) throw new IllegalStateException("invalid transaction barrier");
                if (!release.await(45, TimeUnit.SECONDS)) throw new IllegalStateException("lab barrier timeout");
                if (rollback) throw new IllegalStateException("lab injected rollback after claim");
            }
            return result;
        }
    }

    @RestController
    @RequestMapping("/__lab")
    public static class Control {
        private final Barrier barrier;
        public Control(Barrier barrier) { this.barrier = barrier; }
        private void authorize(String secret) {
            if (!System.getenv("LAB_SECRET").equals(secret)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        @PostMapping("/arm")
        Map<String,Object> arm(@RequestHeader("X-Lab-Secret") String secret, @RequestBody Map<String,Object> body) {
            authorize(secret); barrier.arm((String)body.get("key"), Boolean.TRUE.equals(body.get("rollback")));
            return Map.of("armed", true);
        }
        @GetMapping("/barrier")
        Map<String,Object> status(@RequestHeader("X-Lab-Secret") String secret) { authorize(secret); return barrier.observation; }
        @PostMapping("/release")
        Map<String,Object> release(@RequestHeader("X-Lab-Secret") String secret) {
            authorize(secret); barrier.release.countDown(); return Map.of("released", true);
        }
    }
}
