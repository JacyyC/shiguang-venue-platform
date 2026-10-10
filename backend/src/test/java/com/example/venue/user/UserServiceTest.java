package com.example.venue.user;

import com.example.venue.common.BusinessException;
import com.example.venue.common.Constants;
import com.example.venue.common.CurrentUser;
import com.example.venue.user.dto.AuthorizationSnapshot;
import com.example.venue.user.dto.ChangePasswordRequest;
import com.example.venue.user.dto.UpdateProfileRequest;
import com.example.venue.user.dto.UserProfile;
import com.example.venue.user.entity.SysUser;
import com.example.venue.user.mapper.SysUserMapper;
import com.example.venue.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {
    @Mock
    private SysUserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userMapper, passwordEncoder);
        CurrentUser currentUser = new CurrentUser(7L, "alice", Set.of(Constants.ROLE_USER), Set.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(currentUser, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void updateProfileUsesTargetedCompareAndSetWithoutOverwritingStatusOrPassword() {
        SysUser user = user(7L);
        when(userMapper.selectById(7L)).thenReturn(user);
        when(userMapper.updateProfile(eq(7L), eq("新昵称"), eq("13900000000"), eq("alice@example.test"),
            eq("avatar.png"), any(), eq(4L))).thenReturn(1);
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.nickname = "新昵称";
        request.phone = "13900000000";
        request.email = "alice@example.test";
        request.avatar = "avatar.png";

        UserProfile profile = userService.updateProfile(request);

        assertThat(profile.nickname).isEqualTo("新昵称");
        assertThat(profile.status).isEqualTo(Constants.ACTIVE);
        verify(userMapper).updateProfile(eq(7L), eq("新昵称"), eq("13900000000"), eq("alice@example.test"),
            eq("avatar.png"), any(), eq(4L));
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void currentAuthorizationUsesTheLiveSecurityContextProjection() {
        CurrentUser currentUser = new CurrentUser(7L, "alice",
            Set.of(Constants.ROLE_VENUE_ADMIN, Constants.ROLE_USER), Set.of("venue-admin:view", "user:view"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(currentUser, null, List.of()));

        AuthorizationSnapshot snapshot = userService.currentAuthorization();

        assertThat(snapshot.roles()).containsExactly(Constants.ROLE_USER, Constants.ROLE_VENUE_ADMIN);
        assertThat(snapshot.permissions()).containsExactly("user:view", "venue-admin:view");
    }

    @Test
    void changePasswordUsesPasswordAndVersionCompareAndSet() {
        SysUser user = user(7L);
        when(userMapper.selectById(7L)).thenReturn(user);
        when(passwordEncoder.matches("old-password", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");
        when(userMapper.changePassword(eq(7L), eq("new-hash"), eq("old-hash"), any(), eq(4L))).thenReturn(1);
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.oldPassword = "old-password";
        request.newPassword = "new-password";

        userService.changePassword(request);

        verify(userMapper).changePassword(eq(7L), eq("new-hash"), eq("old-hash"), any(), eq(4L));
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void changePasswordRejectsAConcurrentResetInsteadOfRestoringTheOldSnapshot() {
        SysUser user = user(7L);
        when(userMapper.selectById(7L)).thenReturn(user);
        when(passwordEncoder.matches("old-password", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");
        when(userMapper.changePassword(eq(7L), eq("new-hash"), eq("old-hash"), any(), eq(4L))).thenReturn(0);
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.oldPassword = "old-password";
        request.newPassword = "new-password";

        assertThatThrownBy(() -> userService.changePassword(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("账号状态或密码已变化，请重新登录后重试");

        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    private SysUser user(Long id) {
        SysUser user = new SysUser();
        user.id = id;
        user.username = "alice";
        user.password = "old-hash";
        user.status = Constants.ACTIVE;
        user.deleted = 0;
        user.userVersion = 4L;
        user.credentialVersion = 8L;
        return user;
    }
}
