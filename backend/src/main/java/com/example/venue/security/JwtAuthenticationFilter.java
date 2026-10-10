package com.example.venue.security;

import com.example.venue.common.Constants;
import com.example.venue.common.CurrentUser;
import com.example.venue.user.entity.SysUser;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.stream.Stream;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtTokenService jwtTokenService;
    private final SecurityUserService securityUserService;

    public JwtAuthenticationFilter(JwtTokenService jwtTokenService, SecurityUserService securityUserService) {
        this.jwtTokenService = jwtTokenService;
        this.securityUserService = securityUserService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String header = request.getHeader(Constants.AUTH_HEADER);
        if (header != null && header.startsWith(Constants.TOKEN_PREFIX)) {
            try {
                JwtTokenService.TokenPayload token = jwtTokenService.parseToken(header.substring(Constants.TOKEN_PREFIX.length()));
                SysUser user = securityUserService.loadEnabledUser(token.userId());
                if (credentialVersion(user) != token.credentialVersion()) {
                    throw new IllegalStateException("JWT 凭据已失效");
                }
                CurrentUser currentUser = securityUserService.toCurrentUser(user);
                var authorities = Stream.concat(
                    currentUser.roles().stream().map(role -> "ROLE_" + role),
                    currentUser.permissions().stream()
                ).map(SimpleGrantedAuthority::new).toList();
                var authentication = new UsernamePasswordAuthenticationToken(currentUser, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (ExpiredJwtException ex) {
                log.warn("JWT token 已过期, path={}, msg={}", request.getRequestURI(), ex.getMessage());
                SecurityContextHolder.clearContext();
            } catch (MalformedJwtException | SignatureException ex) {
                log.warn("JWT token 无效/被篡改, path={}, msg={}", request.getRequestURI(), ex.getMessage());
                SecurityContextHolder.clearContext();
            } catch (Exception ex) {
                log.warn("JWT 解析异常, path={}, msg={}", request.getRequestURI(), ex.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private long credentialVersion(SysUser user) {
        return user.credentialVersion == null ? 0L : user.credentialVersion;
    }
}
