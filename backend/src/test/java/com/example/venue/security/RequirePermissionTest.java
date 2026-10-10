package com.example.venue.security;

import com.example.venue.common.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RequirePermissionTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void methodPermissionAllowsMatchingAuthority() {
        authenticate("venue:read");
        PermissionService service = proxied(new PermissionService());

        assertThat(service.read()).isEqualTo("allowed");
    }

    @Test
    void methodPermissionRejectsMissingAuthority() {
        authenticate("venue:other");
        PermissionService service = proxied(new PermissionService());

        assertThatThrownBy(service::read)
            .isInstanceOf(AccessDeniedException.class)
            .hasMessage("无权限访问该资源");
    }

    @Test
    void typePermissionRejectsMissingAuthority() {
        authenticate("venue:read");
        TypeProtectedService service = proxied(new TypeProtectedService());

        assertThatThrownBy(service::manage)
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deniedControllerRequestReturnsForbiddenInsteadOfServerError() throws Exception {
        authenticate("venue:other");
        PermissionController controller = proxied(new PermissionController());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

        mockMvc.perform(get("/test/require-permission"))
            .andExpect(status().isForbidden());
    }

    private void authenticate(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "tester", "n/a", List.of(new SimpleGrantedAuthority(authority))));
    }

    @SuppressWarnings("unchecked")
    private <T> T proxied(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new RequirePermissionAspect());
        return (T) factory.getProxy();
    }

    static class PermissionService {
        @RequirePermission("venue:read")
        public String read() {
            return "allowed";
        }
    }

    @RequirePermission("venue:manage")
    static class TypeProtectedService {
        public String manage() {
            return "allowed";
        }
    }

    @RestController
    static class PermissionController {
        @GetMapping("/test/require-permission")
        @RequirePermission("venue:read")
        public String read() {
            return "allowed";
        }
    }
}
