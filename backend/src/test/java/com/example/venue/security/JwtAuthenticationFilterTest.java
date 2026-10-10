package com.example.venue.security;

import com.example.venue.common.Constants;
import com.example.venue.common.CurrentUser;
import com.example.venue.user.entity.SysUser;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {
    @Mock
    private JwtTokenService jwtTokenService;
    @Mock
    private SecurityUserService securityUserService;
    @Mock
    private FilterChain filterChain;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsAnOldTokenAfterTheCredentialVersionHasChanged() throws Exception {
        SysUser user = user(7L, 3L);
        when(jwtTokenService.parseToken("old-token")).thenReturn(new JwtTokenService.TokenPayload(7L, 2L));
        when(securityUserService.loadEnabledUser(7L)).thenReturn(user);
        MockHttpServletRequest request = request("old-token");

        filter().doFilter(request, new MockHttpServletResponse(), filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(securityUserService, never()).toCurrentUser(user);
        verify(filterChain).doFilter(eq(request), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void authenticatesANewTokenWhenCredentialVersionMatches() throws Exception {
        SysUser user = user(7L, 3L);
        CurrentUser currentUser = new CurrentUser(7L, "alice", Set.of(Constants.ROLE_USER), Set.of("user:view"));
        when(jwtTokenService.parseToken("new-token")).thenReturn(new JwtTokenService.TokenPayload(7L, 3L));
        when(securityUserService.loadEnabledUser(7L)).thenReturn(user);
        when(securityUserService.toCurrentUser(user)).thenReturn(currentUser);
        MockHttpServletRequest request = request("new-token");

        filter().doFilter(request, new MockHttpServletResponse(), filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(currentUser);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .toList())
            .containsExactlyInAnyOrder("ROLE_USER", "user:view");
        verify(filterChain).doFilter(eq(request), org.mockito.ArgumentMatchers.any());
    }

    private JwtAuthenticationFilter filter() {
        return new JwtAuthenticationFilter(jwtTokenService, securityUserService);
    }

    private MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        request.addHeader(Constants.AUTH_HEADER, Constants.TOKEN_PREFIX + token);
        return request;
    }

    private SysUser user(Long id, Long credentialVersion) {
        SysUser user = new SysUser();
        user.id = id;
        user.username = "alice";
        user.status = Constants.ACTIVE;
        user.deleted = 0;
        user.credentialVersion = credentialVersion;
        return user;
    }
}
