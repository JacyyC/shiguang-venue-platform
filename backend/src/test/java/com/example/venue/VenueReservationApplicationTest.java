package com.example.venue;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.stereotype.Controller;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class VenueReservationApplicationTest {
    private static final Set<String> RETIRED_AGENT_V1_PATHS = Set.of(
        "/api/agent/v1/operations-summary:query",
        "/api/agent/v1/funnel-comparison:query",
        "/api/agent/v1/reservation-exceptions:query",
        "/api/agent/v1/feedback-overview:query",
        "/api/agent/v1/promotion-overview:query"
    );

    @Test
    void mapperScanIncludesSharedBusinessMapperPackages() {
        MapperScan mapperScan = VenueReservationApplication.class.getAnnotation(MapperScan.class);

        assertThat(mapperScan).isNotNull();
        assertThat(mapperScan.value())
            .contains("com.example.venue.business.mapper")
            .contains("com.example.venue.resource.mapper")
            .contains("com.example.venue.member.mapper")
            .contains("com.example.venue.promotion.mapper")
            .contains("com.example.venue.checkin.mapper")
            .contains("com.example.venue.funnel.mapper")
            .contains("com.example.venue.payment.mapper")
            .contains("com.example.venue.siteops.mapper")
            .contains("com.example.venue.feedback.mapper")
            .contains("com.example.venue.community.mapper")
            .contains("com.example.venue.community.activity.mapper")
            .contains("com.example.venue.advertising.mapper");
    }

    @Test
    void pomIncludesActuatorForHealthEndpoint() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom).contains("spring-boot-starter-actuator");
    }

    @Test
    void retiredAgentV1RoutesAreAbsentFromProductionMappingsAndSources() throws Exception {
        try (GenericWebApplicationContext context = productionControllerMappingContext()) {
            RequestMappingHandlerMapping mapping = context.getBean(RequestMappingHandlerMapping.class);
            Set<String> mappedPaths = new TreeSet<>();
            mapping.getHandlerMethods().keySet()
                .forEach(mappingInfo -> mappedPaths.addAll(mappingInfo.getPatternValues()));

            assertThat(mappedPaths).contains("/api/auth/login", "/api/reservations");
            assertThat(mappedPaths).doesNotContainAnyElementsOf(RETIRED_AGENT_V1_PATHS);
            assertThat(mappedPaths).noneMatch(path -> path.startsWith("/api/agent/v1"));

            for (String retiredPath : RETIRED_AGENT_V1_PATHS) {
                assertThat(mapping.getHandler(new MockHttpServletRequest("POST", retiredPath)))
                    .as("POST %s must not have a production handler mapping", retiredPath)
                    .isNull();
            }
        }

        try (Stream<Path> paths = Files.walk(Path.of("src/main"))) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                try {
                    String content = Files.readString(path);
                    assertThat(content)
                        .as(path.toString())
                        .doesNotContain("/api/agent/v1")
                        .doesNotContain("reservation-agent-provider-v1")
                        .doesNotContain("com.example.venue.agent");
                } catch (Exception error) {
                    throw new IllegalStateException("Could not read " + path, error);
                }
            });
        }

        assertThat(Files.readString(Path.of("pom.xml")))
            .doesNotContain("swagger-parser")
            .doesNotContain("reservation-agent-provider-v1");
    }

    private GenericWebApplicationContext productionControllerMappingContext() throws ClassNotFoundException {
        GenericWebApplicationContext context = new GenericWebApplicationContext(new MockServletContext());
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        for (BeanDefinition candidate : scanner.findCandidateComponents("com.example.venue")) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            if (!isProductionClass(controller)) {
                continue;
            }
            context.registerBeanDefinition(
                controller.getName(),
                BeanDefinitionBuilder.genericBeanDefinition(controller)
                    .setLazyInit(true)
                    .getBeanDefinition()
            );
        }

        context.registerBeanDefinition(
            "requestMappingHandlerMapping",
            BeanDefinitionBuilder.genericBeanDefinition(RequestMappingHandlerMapping.class).getBeanDefinition()
        );
        context.refresh();
        return context;
    }

    private static boolean isProductionClass(Class<?> candidate) {
        return VenueReservationApplication.class.getProtectionDomain().getCodeSource().getLocation()
            .equals(candidate.getProtectionDomain().getCodeSource().getLocation());
    }
}
