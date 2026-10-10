package com.example.venue.system;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.common.Constants;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.BusinessException;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.system.dto.CreateManagedRoleRequest;
import com.example.venue.system.dto.ManagedRoleResponse;
import com.example.venue.system.dto.PermissionMenuResponse;
import com.example.venue.system.dto.SystemUserResponse;
import com.example.venue.system.dto.RoleOptionResponse;
import com.example.venue.system.dto.UpdateManagedRoleRequest;
import com.example.venue.system.dto.UpdateSystemUserRequest;
import com.example.venue.system.entity.SysConfig;
import com.example.venue.system.entity.SysDeletedUserSnapshot;
import com.example.venue.system.entity.SysOperationLog;
import com.example.venue.system.entity.SysMenu;
import com.example.venue.system.entity.SysRole;
import com.example.venue.system.entity.SysRoleMenu;
import com.example.venue.system.entity.SysUserRole;
import com.example.venue.system.mapper.SysConfigMapper;
import com.example.venue.system.mapper.SysDeletedUserSnapshotMapper;
import com.example.venue.system.mapper.SysDictDataMapper;
import com.example.venue.system.mapper.SysLoginLogMapper;
import com.example.venue.system.mapper.SysMenuMapper;
import com.example.venue.system.mapper.SysOperationLogMapper;
import com.example.venue.system.mapper.SysRoleMapper;
import com.example.venue.system.mapper.SysRoleMenuMapper;
import com.example.venue.system.mapper.SysUserRoleMapper;
import com.example.venue.system.service.SystemService;
import com.example.venue.user.entity.SysUser;
import com.example.venue.user.mapper.SysUserMapper;
import com.example.venue.venue.mapper.VenueAdminRelationMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemServiceTest {
    @Mock
    private SysMenuMapper menuMapper;
    @Mock
    private SysConfigMapper configMapper;
    @Mock
    private SysDictDataMapper dictDataMapper;
    @Mock
    private SysUserMapper userMapper;
    @Mock
    private SysUserRoleMapper userRoleMapper;
    @Mock
    private SysRoleMapper roleMapper;
    @Mock
    private SysRoleMenuMapper roleMenuMapper;
    @Mock
    private SysDeletedUserSnapshotMapper deletedUserSnapshotMapper;
    @Mock
    private VenueAdminRelationMapper venueAdminRelationMapper;
    @Mock
    private SysOperationLogMapper operationLogMapper;
    @Mock
    private SysLoginLogMapper loginLogMapper;
    @Mock
    private PasswordEncoder passwordEncoder;

    private SystemService systemService;

    @BeforeEach
    void setUp() {
        systemService = new SystemService(menuMapper, configMapper, dictDataMapper, userMapper, userRoleMapper,
            roleMapper, roleMenuMapper, deletedUserSnapshotMapper, venueAdminRelationMapper, operationLogMapper,
            loginLogMapper, passwordEncoder);
        lenient().when(roleMapper.findByRoleKeyForUpdate(Constants.ROLE_SYSTEM_ADMIN))
            .thenReturn(role(3L, Constants.ROLE_SYSTEM_ADMIN, Constants.ACTIVE, 0));
        lenient().when(userMapper.updateRoleProjection(anyLong(), anyLong(), any(), anyLong())).thenReturn(1);
        var admin = new CurrentUser(1L, "admin", Set.of(Constants.ROLE_SYSTEM_ADMIN), Set.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void deleteUserRemovesAccountAndAccessRelations() {
        SysUser user = user(3L, "user");
        when(userMapper.selectById(3L)).thenReturn(user);
        when(userMapper.findRoleKeys(3L)).thenReturn(List.of(Constants.ROLE_USER));
        when(userMapper.physicallyDeleteById(3L)).thenReturn(1);

        systemService.deleteUser(3L);

        ArgumentCaptor<SysDeletedUserSnapshot> snapshotCaptor = ArgumentCaptor.forClass(SysDeletedUserSnapshot.class);
        verify(deletedUserSnapshotMapper).insert(snapshotCaptor.capture());
        SysDeletedUserSnapshot snapshot = snapshotCaptor.getValue();
        assertThat(snapshot.userId).isEqualTo(3L);
        assertThat(snapshot.username).isEqualTo("user");
        assertThat(snapshot.roleKeys).isEqualTo(Constants.ROLE_USER);
        assertThat(snapshot.deletedBy).isEqualTo(1L);
        assertThat(snapshot.deletedByUsername).isEqualTo("admin");
        assertThat(snapshot.deidentifyAfter).isEqualTo(snapshot.deletedTime.plusDays(365));
        verify(userMapper).physicallyDeleteById(3L);
        verify(userMapper, never()).updateById(any(SysUser.class));
        verify(userRoleMapper).deleteByUserId(3L);
        verify(venueAdminRelationMapper).deleteByUserId(3L);
    }

    @Test
    void usersReturnsSafeDtosWithoutPassword() {
        SysUser user = user(5L, "alice");
        user.password = "$2a$hash";
        when(userMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Page<SysUser> page = invocation.getArgument(0);
            page.setRecords(List.of(user));
            page.setTotal(1);
            return page;
        });

        PageResult<SystemUserResponse> result = systemService.users(new PageQuery(), null);

        assertThat(result.getRecords()).hasSize(1);
        SystemUserResponse response = result.getRecords().get(0);
        assertThat(response.id).isEqualTo(5L);
        assertThat(response.username).isEqualTo("alice");
        assertThat(response.roleId).isEqualTo(2L);
        assertThat(SystemUserResponse.class.getDeclaredFields())
            .extracting("name")
            .doesNotContain("password");
    }

    @Test
    void assignableRolesReturnsOnlyTheActiveRoleCatalogProjection() {
        SysRole userRole = role(1L, Constants.ROLE_USER, Constants.ACTIVE, 0);
        userRole.roleName = "普通用户";
        userRole.dataScope = "SELF";
        SysRole venueAdminRole = role(2L, Constants.ROLE_VENUE_ADMIN, Constants.ACTIVE, 0);
        venueAdminRole.roleName = "场馆管理员";
        venueAdminRole.dataScope = "VENUE";
        when(roleMapper.selectList(any())).thenReturn(List.of(userRole, venueAdminRole));

        List<RoleOptionResponse> roles = systemService.assignableRoles();

        assertThat(roles).extracting(RoleOptionResponse::id).containsExactly(1L, 2L);
        assertThat(roles).extracting(RoleOptionResponse::name).containsExactly("普通用户", "场馆管理员");
        assertThat(roles).extracting(RoleOptionResponse::dataScope).containsExactly("SELF", "VENUE");
    }

    @Test
    void managedRolesIncludeDisabledRolesAndMarkOnlyBuiltIns() {
        SysRole builtIn = role(1L, Constants.ROLE_USER, Constants.ACTIVE, 0);
        builtIn.roleName = "普通用户";
        SysRole dynamic = role(9L, "CONTENT_REVIEWER", Constants.DISABLED, 0);
        dynamic.roleName = "内容审核员";
        when(roleMapper.selectList(any())).thenReturn(List.of(builtIn, dynamic));

        List<ManagedRoleResponse> roles = systemService.managedRoles();

        assertThat(roles).extracting(ManagedRoleResponse::key)
            .containsExactly(Constants.ROLE_USER, "CONTENT_REVIEWER");
        assertThat(roles).extracting(ManagedRoleResponse::builtIn).containsExactly(true, false);
        assertThat(roles.get(1).status()).isEqualTo(Constants.DISABLED);
    }

    @Test
    void permissionMenusReturnOnlyMapperWhitelistedFields() {
        SysMenu root = menu(100L, 0L, "content-admin:view", "Y");
        root.menuName = "内容运营后台";
        root.path = "/content-admin";
        root.menuType = "MENU";
        SysMenu userManagement = menu(21L, 20L, "system:user:manage", "Y");
        SysMenu roleManagement = menu(22L, 20L, "system:role:manage", "Y");
        when(menuMapper.findActivePermissionMenus()).thenReturn(List.of(root, userManagement, roleManagement));

        List<PermissionMenuResponse> permissions = systemService.permissionMenus();

        assertThat(permissions).containsExactly(new PermissionMenuResponse(
            100L, "内容运营后台", 0L, "/content-admin", "content-admin:view", "MENU", "Y"));
    }

    @Test
    void rolePermissionIdsCanBeReadForBuiltInRoles() {
        when(roleMapper.selectById(3L)).thenReturn(role(3L, Constants.ROLE_SYSTEM_ADMIN, Constants.ACTIVE, 0));
        when(roleMenuMapper.findMenuIdsByRoleId(3L)).thenReturn(List.of(1L, 20L, 24L));

        assertThat(systemService.rolePermissionMenuIds(3L)).containsExactly(1L, 20L, 24L);
    }

    @Test
    void createManagedRoleNormalizesKeyAndKeepsDataScopeConservative() {
        CreateManagedRoleRequest request = new CreateManagedRoleRequest();
        request.roleName = " 内容审核员 ";
        request.roleKey = "content_reviewer";

        ManagedRoleResponse response = systemService.createManagedRole(request);

        ArgumentCaptor<SysRole> captor = ArgumentCaptor.forClass(SysRole.class);
        verify(roleMapper).insert(captor.capture());
        SysRole inserted = captor.getValue();
        assertThat(inserted.roleName).isEqualTo("内容审核员");
        assertThat(inserted.roleKey).isEqualTo("CONTENT_REVIEWER");
        assertThat(inserted.dataScope).isEqualTo("SELF");
        assertThat(inserted.status).isEqualTo(Constants.ACTIVE);
        assertThat(response.key()).isEqualTo("CONTENT_REVIEWER");
        assertThat(response.builtIn()).isFalse();
    }

    @Test
    void createManagedRoleRejectsBuiltInRoleKeys() {
        CreateManagedRoleRequest request = new CreateManagedRoleRequest();
        request.roleName = "伪系统管理员";
        request.roleKey = "system_admin";

        assertThatThrownBy(() -> systemService.createManagedRole(request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("内置角色标识不可用于动态角色");

        verify(roleMapper, never()).insert(any(SysRole.class));
    }

    @Test
    void updateManagedRoleChangesOnlyDynamicRoleNameAndStatus() {
        SysRole role = role(9L, "CONTENT_REVIEWER", Constants.ACTIVE, 0);
        role.roleName = "内容审核员";
        when(roleMapper.findByIdForUpdate(9L)).thenReturn(role);
        UpdateManagedRoleRequest request = new UpdateManagedRoleRequest();
        request.roleName = " 高级内容审核员 ";
        request.status = "disabled";

        ManagedRoleResponse response = systemService.updateManagedRole(9L, request);

        assertThat(role.roleKey).isEqualTo("CONTENT_REVIEWER");
        assertThat(response.name()).isEqualTo("高级内容审核员");
        assertThat(response.status()).isEqualTo(Constants.DISABLED);
        verify(roleMapper).updateById(role);
    }

    @Test
    void updateManagedRoleRejectsBuiltInRoles() {
        SysRole role = role(2L, Constants.ROLE_VENUE_ADMIN, Constants.ACTIVE, 0);
        when(roleMapper.findByIdForUpdate(2L)).thenReturn(role);
        UpdateManagedRoleRequest request = new UpdateManagedRoleRequest();
        request.roleName = "内容运营";
        request.status = Constants.DISABLED;

        assertThatThrownBy(() -> systemService.updateManagedRole(2L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("内置角色不可修改");

        verify(roleMapper, never()).updateById(any(SysRole.class));
    }

    @Test
    void replaceRolePermissionsDeduplicatesAndPersistsAValidTree() {
        when(roleMapper.findByIdForUpdate(9L)).thenReturn(role(9L, "CONTENT_REVIEWER", Constants.ACTIVE, 0));
        SysMenu root = menu(100L, 0L, "content-admin:view", "Y");
        SysMenu reports = menu(101L, 100L, "community:report:review", "Y");
        SysMenu moderate = menu(102L, 101L, "community:content:moderate", "N");
        when(menuMapper.findActivePermissionMenus()).thenReturn(List.of(root, reports, moderate));

        systemService.replaceRolePermissions(9L, List.of(100L, 101L, 102L, 102L));

        verify(roleMenuMapper).deleteByRoleId(9L);
        ArgumentCaptor<SysRoleMenu> captor = ArgumentCaptor.forClass(SysRoleMenu.class);
        verify(roleMenuMapper, org.mockito.Mockito.times(3)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(relation -> relation.menuId)
            .containsExactly(100L, 101L, 102L);
    }

    @Test
    void replaceRolePermissionsRejectsVisibleChildWithoutItsParent() {
        when(roleMapper.findByIdForUpdate(9L)).thenReturn(role(9L, "CONTENT_REVIEWER", Constants.ACTIVE, 0));
        SysMenu root = menu(100L, 0L, "content-admin:view", "Y");
        SysMenu reports = menu(101L, 100L, "community:report:review", "Y");
        when(menuMapper.findActivePermissionMenus()).thenReturn(List.of(root, reports));

        assertThatThrownBy(() -> systemService.replaceRolePermissions(9L, List.of(101L)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("可见子菜单必须同时分配父级权限");

        verify(roleMenuMapper, never()).deleteByRoleId(9L);
    }

    @Test
    void replaceRolePermissionsRejectsBuiltInRolesBeforeDeletingRelations() {
        when(roleMapper.findByIdForUpdate(3L))
            .thenReturn(role(3L, Constants.ROLE_SYSTEM_ADMIN, Constants.ACTIVE, 0));

        assertThatThrownBy(() -> systemService.replaceRolePermissions(3L, List.of()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("内置角色不可修改");

        verify(roleMenuMapper, never()).deleteByRoleId(3L);
    }

    @Test
    void userRoleIdsReturnsTheEffectiveRoleSelectionForAnExistingUser() {
        when(userMapper.selectById(3L)).thenReturn(user(3L, "venue-admin"));
        when(userRoleMapper.findActiveRoleIdsByUserId(3L)).thenReturn(List.of(1L, 2L));

        assertThat(systemService.userRoleIds(3L)).containsExactly(1L, 2L);

        verify(userRoleMapper).findActiveRoleIdsByUserId(3L);
    }

    @Test
    void updateUserChangesEditableProfileFields() {
        SysUser user = user(3L, "old_name");
        when(userMapper.selectById(3L)).thenReturn(user);
        when(userMapper.findByUsername("new_name")).thenReturn(null);
        when(userMapper.updateSystemUser(anyLong(), any(), any(), any(), any(), any(), any(), anyLong())).thenReturn(1);
        UpdateSystemUserRequest request = updateUserRequest("new_name", "新昵称", "13900000000", "new@example.com", Constants.ACTIVE);

        SystemUserResponse response = systemService.updateUser(3L, request);

        assertThat(response.username).isEqualTo("new_name");
        assertThat(response.nickname).isEqualTo("新昵称");
        assertThat(response.phone).isEqualTo("13900000000");
        assertThat(response.email).isEqualTo("new@example.com");
        assertThat(response.status).isEqualTo(Constants.ACTIVE);
        verify(userMapper).updateSystemUser(eq(3L), eq("new_name"), eq("新昵称"), eq("13900000000"),
            eq("new@example.com"), eq(Constants.ACTIVE), any(), eq(0L));
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void updateUserRejectsDuplicateUsername() {
        SysUser user = user(3L, "alice");
        SysUser duplicate = user(4L, "bob");
        when(userMapper.selectById(3L)).thenReturn(user);
        when(userMapper.findByUsername("bob")).thenReturn(duplicate);
        UpdateSystemUserRequest request = updateUserRequest("bob", "Bob", null, null, Constants.ACTIVE);

        assertThatThrownBy(() -> systemService.updateUser(3L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("用户名已存在");

        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void updateUserStatusRejectsUnknownStatus() {
        assertThatThrownBy(() -> systemService.updateUserStatus(3L, "LOCKED"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("用户状态不合法");

        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void updateUserStatusRejectsDisablingCurrentUser() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "admin"));

        assertThatThrownBy(() -> systemService.updateUserStatus(1L, Constants.DISABLED))
            .isInstanceOf(BusinessException.class)
            .hasMessage("不能禁用当前登录用户");

        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void updateUserStatusRejectsDisablingLastSystemAdmin() {
        when(userMapper.selectById(2L)).thenReturn(user(2L, "root"));
        when(userMapper.findRoleKeys(2L)).thenReturn(List.of(Constants.ROLE_SYSTEM_ADMIN));
        when(userMapper.countActiveUsersByRoleKey(Constants.ROLE_SYSTEM_ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> systemService.updateUserStatus(2L, Constants.DISABLED))
            .isInstanceOf(BusinessException.class)
            .hasMessage("不能禁用最后一个系统管理员账号");

        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void updateUserStatusUsesTargetedCompareAndSetUpdate() {
        when(userMapper.selectById(3L)).thenReturn(user(3L, "user"));
        when(userMapper.updateUserStatus(eq(3L), eq(Constants.DISABLED), any(), eq(0L))).thenReturn(1);

        systemService.updateUserStatus(3L, Constants.DISABLED);

        verify(userMapper).updateUserStatus(eq(3L), eq(Constants.DISABLED), any(), eq(0L));
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void assignRolesRejectsMissingRoleBeforeDeletingRelations() {
        when(roleMapper.selectByIds(any())).thenReturn(List.of(role(1L, Constants.ROLE_USER, Constants.ACTIVE, 0)));

        assertThatThrownBy(() -> systemService.assignRoles(3L, List.of(1L, 99L)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("角色不存在");

        verify(userRoleMapper, never()).deleteByUserId(3L);
        verify(userRoleMapper, never()).insert(any(SysUserRole.class));
    }

    @Test
    void assignRolesDeduplicatesAndStoresOnlyEnabledRoles() {
        SysUser user = user(3L, "user");
        user.roleId = 9L;
        user.userVersion = 4L;
        when(userMapper.selectById(3L)).thenReturn(user);
        when(roleMapper.selectByIds(any())).thenReturn(List.of(
            role(1L, Constants.ROLE_USER, Constants.ACTIVE, 0),
            role(2L, Constants.ROLE_VENUE_ADMIN, Constants.ACTIVE, 0)
        ));

        systemService.assignRoles(3L, List.of(1L, 2L, 1L));

        ArgumentCaptor<SysUserRole> relationCaptor = ArgumentCaptor.forClass(SysUserRole.class);
        verify(userRoleMapper).deleteByUserId(3L);
        verify(userRoleMapper, org.mockito.Mockito.times(2)).insert(relationCaptor.capture());
        assertThat(relationCaptor.getAllValues()).extracting(relation -> relation.roleId).containsExactly(1L, 2L);
        verify(userMapper).updateRoleProjection(eq(3L), eq(1L), any(), eq(4L));
        assertThat(user.roleId).isEqualTo(1L);
        assertThat(user.userVersion).isEqualTo(5L);
        assertThat(SystemUserResponse.from(user).roleId).isEqualTo(1L);
    }

    @Test
    void assignRolesRejectsConcurrentUserChangesBeforeReplacingRelations() {
        SysUser user = user(3L, "user");
        user.userVersion = 8L;
        when(userMapper.selectById(3L)).thenReturn(user);
        when(roleMapper.selectByIds(any())).thenReturn(List.of(role(2L, Constants.ROLE_VENUE_ADMIN, Constants.ACTIVE, 0)));
        when(userMapper.updateRoleProjection(eq(3L), eq(2L), any(), eq(8L))).thenReturn(0);

        assertThatThrownBy(() -> systemService.assignRoles(3L, List.of(2L)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("用户资料已被其他操作更新，请刷新后重试");

        verify(userRoleMapper, never()).deleteByUserId(3L);
        verify(userRoleMapper, never()).insert(any(SysUserRole.class));
        verify(venueAdminRelationMapper, never()).deleteByUserId(3L);
    }

    @Test
    void assignRolesRejectsRemovingTheLastEffectiveSystemAdministrator() {
        when(userMapper.selectById(3L)).thenReturn(user(3L, "root"));
        when(userMapper.findRoleKeys(3L)).thenReturn(List.of(Constants.ROLE_SYSTEM_ADMIN));
        when(userMapper.countActiveUsersByRoleKey(Constants.ROLE_SYSTEM_ADMIN)).thenReturn(1L);
        when(roleMapper.selectByIds(any())).thenReturn(List.of(role(1L, Constants.ROLE_USER, Constants.ACTIVE, 0)));

        assertThatThrownBy(() -> systemService.assignRoles(3L, List.of(1L)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("不能移除最后一个系统管理员角色");

        verify(userRoleMapper, never()).deleteByUserId(3L);
        verify(userRoleMapper, never()).insert(any(SysUserRole.class));
    }

    @Test
    void assignRolesAllowsRemovingSystemAdministratorWhenAnotherEffectiveAdministratorExists() {
        when(userMapper.selectById(3L)).thenReturn(user(3L, "root"));
        when(userMapper.findRoleKeys(3L)).thenReturn(List.of(Constants.ROLE_SYSTEM_ADMIN));
        when(userMapper.countActiveUsersByRoleKey(Constants.ROLE_SYSTEM_ADMIN)).thenReturn(2L);
        when(roleMapper.selectByIds(any())).thenReturn(List.of(role(1L, Constants.ROLE_USER, Constants.ACTIVE, 0)));

        systemService.assignRoles(3L, List.of(1L));

        verify(userRoleMapper).deleteByUserId(3L);
        verify(userRoleMapper).insert(any(SysUserRole.class));
    }

    @Test
    void assignRolesRemovesVenueRelationsWhenVenueAdminRoleIsRemoved() {
        when(userMapper.selectById(3L)).thenReturn(user(3L, "venue-admin"));
        when(userMapper.findRoleKeys(3L)).thenReturn(List.of(Constants.ROLE_VENUE_ADMIN));
        when(roleMapper.selectByIds(any())).thenReturn(List.of(role(1L, Constants.ROLE_USER, Constants.ACTIVE, 0)));

        systemService.assignRoles(3L, List.of(1L));

        verify(venueAdminRelationMapper).deleteByUserId(3L);
    }

    @Test
    void resetUserPasswordUsesTargetedCredentialVersionUpdate() {
        when(userMapper.selectById(3L)).thenReturn(user(3L, "user"));
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-password");
        when(userMapper.resetPassword(eq(3L), eq("encoded-password"), any(), eq(0L))).thenReturn(1);

        systemService.resetUserPassword(3L, "new-password");

        verify(userMapper).resetPassword(eq(3L), eq("encoded-password"), any(), eq(0L));
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void updateConfigRejectsOutOfRangeBusinessNumbers() {
        SysConfig config = new SysConfig();
        config.configKey = "payment.timeout_minutes";
        config.configValue = "30";
        config.valueType = "NUMBER";
        config.editable = "Y";
        when(configMapper.findByKey("payment.timeout_minutes")).thenReturn(config);

        assertThatThrownBy(() -> systemService.updateConfig("payment.timeout_minutes", "0"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("payment.timeout_minutes");
        verify(configMapper, never()).updateById(any(SysConfig.class));
    }

    @Test
    void updateVenueAdminAccessReplacesOnlyValidatedVenueRelations() {
        when(userMapper.selectById(3L)).thenReturn(user(3L, "venue-admin"));
        when(userMapper.findRoleKeys(3L)).thenReturn(List.of(Constants.ROLE_VENUE_ADMIN));
        when(venueAdminRelationMapper.countValidVenue(11L)).thenReturn(1);
        when(venueAdminRelationMapper.countValidVenue(12L)).thenReturn(1);

        systemService.updateVenueAdminAccess(3L, List.of(11L, 12L, 11L));

        verify(venueAdminRelationMapper).deleteByUserId(3L);
        ArgumentCaptor<com.example.venue.venue.entity.VenueAdminRelation> relationCaptor =
            ArgumentCaptor.forClass(com.example.venue.venue.entity.VenueAdminRelation.class);
        verify(venueAdminRelationMapper, org.mockito.Mockito.times(2)).insert(relationCaptor.capture());
        assertThat(relationCaptor.getAllValues())
            .extracting(relation -> relation.venueId)
            .containsExactly(11L, 12L);
    }

    @Test
    void updateVenueAdminAccessRejectsUsersWithoutAnEffectiveVenueAdminRole() {
        when(userMapper.selectById(3L)).thenReturn(user(3L, "user"));
        when(userMapper.findRoleKeys(3L)).thenReturn(List.of(Constants.ROLE_USER));

        assertThatThrownBy(() -> systemService.updateVenueAdminAccess(3L, List.of(11L)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("用户未拥有有效场馆管理员角色");

        verify(venueAdminRelationMapper, never()).deleteByUserId(3L);
    }

    @Test
    void updateVenueAdminAccessRequiresASystemAdministratorActor() {
        CurrentUser venueAdmin = new CurrentUser(8L, "venue-admin", Set.of(Constants.ROLE_VENUE_ADMIN), Set.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(venueAdmin, null, List.of()));

        assertThatThrownBy(() -> systemService.updateVenueAdminAccess(3L, List.of(11L)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("仅系统管理员可维护场馆授权");

        verify(venueAdminRelationMapper, never()).deleteByUserId(3L);
    }

    @Test
    void updateVenueAdminAccessRejectsDeletedVenueBeforeReplacingRelations() {
        when(userMapper.selectById(3L)).thenReturn(user(3L, "venue-admin"));
        when(userMapper.findRoleKeys(3L)).thenReturn(List.of(Constants.ROLE_VENUE_ADMIN));
        when(venueAdminRelationMapper.countValidVenue(11L)).thenReturn(0);

        assertThatThrownBy(() -> systemService.updateVenueAdminAccess(3L, List.of(11L)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("场馆不存在或已删除");

        verify(venueAdminRelationMapper, never()).deleteByUserId(3L);
    }

    @Test
    void updateConfigRejectsInvalidBooleanValue() {
        SysConfig config = config("reservation.auto_approval", "BOOLEAN");
        when(configMapper.findByKey("reservation.auto_approval")).thenReturn(config);

        assertThatThrownBy(() -> systemService.updateConfig("reservation.auto_approval", "not-boolean"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("配置值必须是 true 或 false");

        verify(configMapper, never()).updateById(any(SysConfig.class));
    }

    @Test
    void updateConfigRejectsInvalidNumberValue() {
        SysConfig config = config("payment.timeout_minutes", "NUMBER");
        when(configMapper.findByKey("payment.timeout_minutes")).thenReturn(config);

        assertThatThrownBy(() -> systemService.updateConfig("payment.timeout_minutes", "1.5"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("配置值必须是整数");

        verify(configMapper, never()).updateById(any(SysConfig.class));
    }

    @Test
    void updateConfigNormalizesBooleanValue() {
        SysConfig config = config("reservation.auto_approval", "BOOLEAN");
        when(configMapper.findByKey("reservation.auto_approval")).thenReturn(config);

        systemService.updateConfig("reservation.auto_approval", "TRUE");

        assertThat(config.configValue).isEqualTo("true");
        verify(configMapper).updateById(config);
    }

    @Test
    void operationLogsMaskHistoricalSensitiveValuesBeforeReturningThem() {
        SysOperationLog log = new SysOperationLog();
        log.params = "{\"email\":\"alice@example.test\",\"password\":\"secret\"}";
        log.errorMessage = "请联系 13912345678 处理订单 ORD20260717000001";
        Page<SysOperationLog> page = new Page<>(1, 10);
        page.setRecords(List.of(log));
        page.setTotal(1);
        when(operationLogMapper.selectPage(any(Page.class), any())).thenReturn(page);

        PageQuery query = new PageQuery();
        query.setPageNum(1);
        query.setPageSize(10);
        PageResult<SysOperationLog> result = systemService.operationLogs(query);

        SysOperationLog returned = result.getRecords().get(0);
        assertThat(returned.params + returned.errorMessage)
            .doesNotContain("alice@example.test", "secret", "13912345678", "ORD20260717000001");
    }

    private SysUser user(Long id, String username) {
        SysUser user = new SysUser();
        user.id = id;
        user.username = username;
        user.nickname = username;
        user.status = Constants.ACTIVE;
        user.deleted = 0;
        user.roleId = 2L;
        return user;
    }

    private SysRole role(Long id, String roleKey, String status, Integer deleted) {
        SysRole role = new SysRole();
        role.id = id;
        role.roleKey = roleKey;
        role.status = status;
        role.deleted = deleted;
        return role;
    }

    private SysMenu menu(Long id, Long parentId, String permission, String visible) {
        SysMenu menu = new SysMenu();
        menu.id = id;
        menu.parentId = parentId;
        menu.perms = permission;
        menu.visible = visible;
        menu.status = Constants.ACTIVE;
        menu.deleted = 0;
        return menu;
    }

    private SysConfig config(String key, String valueType) {
        SysConfig config = new SysConfig();
        config.configKey = key;
        config.configValue = "old";
        config.valueType = valueType;
        config.editable = "Y";
        return config;
    }

    private UpdateSystemUserRequest updateUserRequest(String username, String nickname, String phone, String email, String status) {
        UpdateSystemUserRequest request = new UpdateSystemUserRequest();
        request.username = username;
        request.nickname = nickname;
        request.phone = phone;
        request.email = email;
        request.status = status;
        return request;
    }
}
