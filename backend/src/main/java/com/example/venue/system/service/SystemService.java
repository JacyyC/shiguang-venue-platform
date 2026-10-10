package com.example.venue.system.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.common.BusinessException;
import com.example.venue.common.Constants;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.common.SensitiveDataMasker;
import com.example.venue.security.SecurityContext;
import com.example.venue.system.dto.CreateManagedRoleRequest;
import com.example.venue.system.dto.ManagedRoleResponse;
import com.example.venue.system.dto.MenuNode;
import com.example.venue.system.dto.PermissionMenuResponse;
import com.example.venue.system.dto.RoleOptionResponse;
import com.example.venue.system.dto.SystemUserResponse;
import com.example.venue.system.dto.UpdateManagedRoleRequest;
import com.example.venue.system.dto.UpdateSystemUserRequest;
import com.example.venue.system.entity.*;
import com.example.venue.system.mapper.*;
import com.example.venue.user.entity.SysUser;
import com.example.venue.user.mapper.SysUserMapper;
import com.example.venue.venue.mapper.VenueAdminRelationMapper;
import com.example.venue.venue.entity.VenueAdminRelation;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SystemService {
    private static final Set<String> BUILT_IN_ROLE_KEYS = Set.of(
        Constants.ROLE_USER,
        Constants.ROLE_VENUE_ADMIN,
        Constants.ROLE_SYSTEM_ADMIN
    );
    private static final Set<String> NON_DELEGABLE_PERMISSIONS = Set.of(
        "system:user:manage",
        "system:role:manage"
    );

    private final SysMenuMapper menuMapper;
    private final SysConfigMapper configMapper;
    private final SysDictDataMapper dictDataMapper;
    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysDeletedUserSnapshotMapper deletedUserSnapshotMapper;
    private final VenueAdminRelationMapper venueAdminRelationMapper;
    private final SysOperationLogMapper operationLogMapper;
    private final SysLoginLogMapper loginLogMapper;
    private final PasswordEncoder passwordEncoder;

    public SystemService(SysMenuMapper menuMapper, SysConfigMapper configMapper, SysDictDataMapper dictDataMapper,
                         SysUserMapper userMapper, SysUserRoleMapper userRoleMapper, SysRoleMapper roleMapper,
                         SysRoleMenuMapper roleMenuMapper,
                         SysDeletedUserSnapshotMapper deletedUserSnapshotMapper,
                         VenueAdminRelationMapper venueAdminRelationMapper,
                         SysOperationLogMapper operationLogMapper, SysLoginLogMapper loginLogMapper,
                         PasswordEncoder passwordEncoder) {
        this.menuMapper = menuMapper;
        this.configMapper = configMapper;
        this.dictDataMapper = dictDataMapper;
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.deletedUserSnapshotMapper = deletedUserSnapshotMapper;
        this.venueAdminRelationMapper = venueAdminRelationMapper;
        this.operationLogMapper = operationLogMapper;
        this.loginLogMapper = loginLogMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public List<MenuNode> currentMenus() {
        Long userId = SecurityContext.currentUserId();
        List<MenuNode> nodes = menuMapper.findMenusByUserId(userId).stream().map(MenuNode::from).toList();
        Map<Long, MenuNode> byId = nodes.stream().collect(Collectors.toMap(node -> node.id, node -> node, (a, b) -> a, LinkedHashMap::new));
        List<MenuNode> roots = new ArrayList<>();
        for (MenuNode node : byId.values()) {
            if (node.parentId == null || node.parentId == 0 || !byId.containsKey(node.parentId)) {
                roots.add(node);
            } else {
                byId.get(node.parentId).children.add(node);
            }
        }
        return roots;
    }

    public List<SysConfig> configs() {
        return configMapper.selectList(new QueryWrapper<SysConfig>().orderByAsc("config_key"));
    }

    public List<RoleOptionResponse> assignableRoles() {
        List<SysRole> roles = roleMapper.selectList(new QueryWrapper<SysRole>()
            .eq("deleted", 0)
            .eq("status", Constants.ACTIVE)
            .orderByAsc("id"));
        return roles == null ? List.of() : roles.stream().map(RoleOptionResponse::from).toList();
    }

    public List<ManagedRoleResponse> managedRoles() {
        List<SysRole> roles = roleMapper.selectList(new QueryWrapper<SysRole>()
            .eq("deleted", 0)
            .orderByAsc("id"));
        return roles == null ? List.of() : roles.stream()
            .map(role -> ManagedRoleResponse.from(role, isBuiltInRole(role)))
            .toList();
    }

    public List<PermissionMenuResponse> permissionMenus() {
        return delegablePermissionMenus().stream().map(PermissionMenuResponse::from).toList();
    }

    public List<Long> rolePermissionMenuIds(Long roleId) {
        requireRole(roleId);
        List<Long> menuIds = roleMenuMapper.findMenuIdsByRoleId(roleId);
        return menuIds == null ? List.of() : menuIds;
    }

    @Transactional
    public ManagedRoleResponse createManagedRole(CreateManagedRoleRequest request) {
        String roleName = normalizeRoleName(request == null ? null : request.roleName);
        String roleKey = normalizeRoleKey(request == null ? null : request.roleKey);
        if (BUILT_IN_ROLE_KEYS.contains(roleKey)) {
            throw BusinessException.badRequest("内置角色标识不可用于动态角色");
        }
        if (roleMapper.findByRoleKeyForUpdate(roleKey) != null) {
            throw BusinessException.conflict("角色标识已存在");
        }
        LocalDateTime now = LocalDateTime.now();
        SysRole role = new SysRole();
        role.roleName = roleName;
        role.roleKey = roleKey;
        role.dataScope = "SELF";
        role.status = Constants.ACTIVE;
        role.createTime = now;
        role.updateTime = now;
        role.deleted = 0;
        roleMapper.insert(role);
        return ManagedRoleResponse.from(role, false);
    }

    @Transactional
    public ManagedRoleResponse updateManagedRole(Long roleId, UpdateManagedRoleRequest request) {
        SysRole role = requireRoleForUpdate(roleId);
        requireMutableRole(role);
        role.roleName = normalizeRoleName(request == null ? null : request.roleName);
        role.status = normalizeRoleStatus(request == null ? null : request.status);
        role.updateTime = LocalDateTime.now();
        roleMapper.updateById(role);
        return ManagedRoleResponse.from(role, false);
    }

    @Transactional
    public void replaceRolePermissions(Long roleId, List<Long> menuIds) {
        SysRole role = requireRoleForUpdate(roleId);
        requireMutableRole(role);
        LinkedHashSet<Long> normalizedMenuIds = normalizePermissionMenuIds(menuIds);
        Map<Long, SysMenu> availableMenus = activePermissionMenusById();
        for (Long menuId : normalizedMenuIds) {
            if (!availableMenus.containsKey(menuId)) {
                throw BusinessException.badRequest("权限菜单不存在或已停用");
            }
        }
        for (Long menuId : normalizedMenuIds) {
            SysMenu menu = availableMenus.get(menuId);
            if ("Y".equals(menu.visible) && menu.parentId != null && menu.parentId > 0
                && availableMenus.containsKey(menu.parentId) && !normalizedMenuIds.contains(menu.parentId)) {
                throw BusinessException.badRequest("可见子菜单必须同时分配父级权限");
            }
        }
        roleMenuMapper.deleteByRoleId(roleId);
        for (Long menuId : normalizedMenuIds) {
            SysRoleMenu relation = new SysRoleMenu();
            relation.roleId = roleId;
            relation.menuId = menuId;
            roleMenuMapper.insert(relation);
        }
    }

    @Transactional
    public void updateConfig(String key, String value) {
        SysConfig config = configMapper.findByKey(key);
        if (config == null) {
            throw BusinessException.notFound("系统参数不存在");
        }
        if ("N".equals(config.editable)) {
            throw BusinessException.badRequest("该系统参数不可编辑");
        }
        config.configValue = validateConfigValue(config, value);
        config.updateTime = LocalDateTime.now();
        configMapper.updateById(config);
    }

    public List<SysDictData> dict(String type) {
        return dictDataMapper.findByType(type);
    }

    public PageResult<SystemUserResponse> users(PageQuery query, String keyword) {
        QueryWrapper<SysUser> wrapper = new QueryWrapper<SysUser>().eq("deleted", 0);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like("username", keyword).or().like("nickname", keyword).or().like("phone", keyword));
        }
        Page<SysUser> page = userMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()), wrapper.orderByDesc("id"));
        return PageResult.of(page.getRecords().stream().map(SystemUserResponse::from).toList(), page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    @Transactional
    public SystemUserResponse updateUser(Long userId, UpdateSystemUserRequest request) {
        SysUser user = requireActiveRecordUser(userId);
        String username = request.username == null ? "" : request.username.trim();
        if (username.isBlank()) {
            throw BusinessException.badRequest("用户名不能为空");
        }
        SysUser sameUsernameUser = userMapper.findByUsername(username);
        if (sameUsernameUser != null && !Objects.equals(sameUsernameUser.id, user.id)) {
            throw BusinessException.conflict("用户名已存在");
        }
        String normalizedStatus = normalizeUserStatus(request.status);
        if (Constants.DISABLED.equals(normalizedStatus)) {
            ensureUserCanBeDisabled(user);
        }
        String nickname = trimToNull(request.nickname);
        String phone = trimToNull(request.phone);
        String email = trimToNull(request.email);
        LocalDateTime updateTime = LocalDateTime.now();
        if (userMapper.updateSystemUser(user.id, username, nickname, phone, email, normalizedStatus,
            updateTime, userVersion(user)) == 0) {
            throw BusinessException.conflict("用户资料已被其他操作更新，请刷新后重试");
        }
        user.username = username;
        user.nickname = nickname;
        user.phone = phone;
        user.email = email;
        user.status = normalizedStatus;
        user.updateTime = updateTime;
        user.userVersion = userVersion(user) + 1;
        return SystemUserResponse.from(user);
    }

    @Transactional
    public void updateUserStatus(Long userId, String status) {
        String normalizedStatus = normalizeUserStatus(status);
        SysUser user = requireActiveRecordUser(userId);
        if (Constants.DISABLED.equals(normalizedStatus)) {
            ensureUserCanBeDisabled(user);
        }
        LocalDateTime updateTime = LocalDateTime.now();
        if (userMapper.updateUserStatus(user.id, normalizedStatus, updateTime, userVersion(user)) == 0) {
            throw BusinessException.conflict("用户状态已被其他操作更新，请刷新后重试");
        }
    }

    @Transactional
    public void assignRoles(Long userId, List<Long> roleIds) {
        LinkedHashSet<Long> normalizedRoleIds = normalizeRoleIds(roleIds);
        Map<Long, SysRole> assignedRoles = requireAssignableRoles(normalizedRoleIds);
        if (roleMapper.findByRoleKeyForUpdate(Constants.ROLE_SYSTEM_ADMIN) == null) {
            throw BusinessException.badRequest("系统管理员角色不存在");
        }
        SysUser user = requireActiveRecordUser(userId);
        List<String> currentRoleKeys = userMapper.findRoleKeys(userId);
        boolean assignedSystemAdmin = assignedRoles.values().stream()
            .anyMatch(role -> Constants.ROLE_SYSTEM_ADMIN.equals(role.roleKey));
        if (currentRoleKeys.contains(Constants.ROLE_SYSTEM_ADMIN) && !assignedSystemAdmin
            && userMapper.countActiveUsersByRoleKey(Constants.ROLE_SYSTEM_ADMIN) <= 1) {
            throw BusinessException.badRequest("不能移除最后一个系统管理员角色");
        }
        Long primaryRoleId = normalizedRoleIds.iterator().next();
        LocalDateTime updateTime = LocalDateTime.now();
        if (userMapper.updateRoleProjection(user.id, primaryRoleId, updateTime, userVersion(user)) == 0) {
            throw BusinessException.conflict("用户资料已被其他操作更新，请刷新后重试");
        }
        userRoleMapper.deleteByUserId(userId);
        for (Long roleId : normalizedRoleIds) {
            SysUserRole relation = new SysUserRole();
            relation.userId = userId;
            relation.roleId = roleId;
            userRoleMapper.insert(relation);
        }
        boolean assignedVenueAdmin = assignedRoles.values().stream()
            .anyMatch(role -> Constants.ROLE_VENUE_ADMIN.equals(role.roleKey));
        if (!assignedVenueAdmin) {
            venueAdminRelationMapper.deleteByUserId(userId);
        }
        user.roleId = primaryRoleId;
        user.updateTime = updateTime;
        user.userVersion = userVersion(user) + 1;
    }

    public List<Long> userRoleIds(Long userId) {
        requireActiveRecordUser(userId);
        List<Long> roleIds = userRoleMapper.findActiveRoleIdsByUserId(userId);
        return roleIds == null ? List.of() : roleIds;
    }

    @Transactional
    public void resetUserPassword(Long userId, String password) {
        if (password == null || password.isBlank()) {
            throw BusinessException.badRequest("新密码不能为空");
        }
        SysUser user = requireActiveRecordUser(userId);
        if (userMapper.resetPassword(user.id, passwordEncoder.encode(password), LocalDateTime.now(), userVersion(user)) == 0) {
            throw BusinessException.conflict("用户密码已被其他操作更新，请刷新后重试");
        }
    }

    public List<Long> venueAccessIds(Long userId) {
        requireSystemAdministrator();
        requireActiveRecordUser(userId);
        return venueAdminRelationMapper.findVenueIdsByUserId(userId);
    }

    @Transactional
    public void updateVenueAdminAccess(Long userId, List<Long> venueIds) {
        requireSystemAdministrator();
        requireActiveRecordUser(userId);
        if (!userMapper.findRoleKeys(userId).contains(Constants.ROLE_VENUE_ADMIN)) {
            throw BusinessException.badRequest("用户未拥有有效场馆管理员角色");
        }
        LinkedHashSet<Long> normalizedVenueIds = normalizeVenueIds(venueIds);
        for (Long venueId : normalizedVenueIds) {
            if (venueAdminRelationMapper.countValidVenue(venueId) == 0) {
                throw BusinessException.badRequest("场馆不存在或已删除");
            }
        }
        venueAdminRelationMapper.deleteByUserId(userId);
        for (Long venueId : normalizedVenueIds) {
            VenueAdminRelation relation = new VenueAdminRelation();
            relation.userId = userId;
            relation.venueId = venueId;
            venueAdminRelationMapper.insert(relation);
        }
    }

    @Transactional
    public void deleteUser(Long userId) {
        SysUser user = requireActiveRecordUser(userId);
        if (Objects.equals(user.id, SecurityContext.currentUserId())) {
            throw BusinessException.badRequest("不能删除当前登录用户");
        }
        List<String> roleKeys = userMapper.findRoleKeys(userId);
        if (roleKeys.contains(Constants.ROLE_SYSTEM_ADMIN)) {
            throw BusinessException.badRequest("不能删除系统管理员账号");
        }
        saveDeletedUserSnapshot(user, roleKeys);
        userRoleMapper.deleteByUserId(userId);
        venueAdminRelationMapper.deleteByUserId(userId);
        if (userMapper.physicallyDeleteById(userId) == 0) {
            throw BusinessException.notFound("用户不存在");
        }
    }

    public PageResult<SysLoginLog> loginLogs(PageQuery query) {
        Page<SysLoginLog> page = loginLogMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()),
            new QueryWrapper<SysLoginLog>().orderByDesc("create_time"));
        return PageResult.of(page.getRecords(), page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    public PageResult<SysOperationLog> operationLogs(PageQuery query) {
        Page<SysOperationLog> page = operationLogMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()),
            new QueryWrapper<SysOperationLog>().orderByDesc("create_time"));
        page.getRecords().forEach(log -> {
            log.params = SensitiveDataMasker.mask(log.params);
            log.errorMessage = SensitiveDataMasker.mask(log.errorMessage);
        });
        return PageResult.of(page.getRecords(), page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    private SysUser requireActiveRecordUser(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.deleted != null && user.deleted == 1) {
            throw BusinessException.notFound("用户不存在");
        }
        return user;
    }

    private String normalizeUserStatus(String status) {
        if (status == null || status.isBlank()) {
            throw BusinessException.badRequest("用户状态不能为空");
        }
        String normalizedStatus = status.trim().toUpperCase(Locale.ROOT);
        if (!Constants.ACTIVE.equals(normalizedStatus) && !Constants.DISABLED.equals(normalizedStatus)) {
            throw BusinessException.badRequest("用户状态不合法");
        }
        return normalizedStatus;
    }

    private void ensureUserCanBeDisabled(SysUser user) {
        if (Objects.equals(user.id, SecurityContext.currentUserId())) {
            throw BusinessException.badRequest("不能禁用当前登录用户");
        }
        List<String> roleKeys = userMapper.findRoleKeys(user.id);
        if (roleKeys.contains(Constants.ROLE_SYSTEM_ADMIN)) {
            long activeSystemAdmins = userMapper.countActiveUsersByRoleKey(Constants.ROLE_SYSTEM_ADMIN);
            if (activeSystemAdmins <= 1) {
                throw BusinessException.badRequest("不能禁用最后一个系统管理员账号");
            }
            throw BusinessException.badRequest("不能禁用系统管理员账号");
        }
    }

    private LinkedHashSet<Long> normalizeRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            throw BusinessException.badRequest("角色不能为空");
        }
        LinkedHashSet<Long> normalizedRoleIds = new LinkedHashSet<>();
        for (Long roleId : roleIds) {
            if (roleId == null || roleId <= 0) {
                throw BusinessException.badRequest("角色 ID 不合法");
            }
            normalizedRoleIds.add(roleId);
        }
        return normalizedRoleIds;
    }

    private Map<Long, SysRole> requireAssignableRoles(Set<Long> roleIds) {
        List<SysRole> roles = roleMapper.selectByIds(roleIds);
        Map<Long, SysRole> byId = roles == null
            ? Map.of()
            : roles.stream().collect(Collectors.toMap(role -> role.id, role -> role, (left, right) -> left));
        for (Long roleId : roleIds) {
            SysRole role = byId.get(roleId);
            if (role == null || role.deleted != null && role.deleted == 1) {
                throw BusinessException.badRequest("角色不存在");
            }
            if (!Constants.ACTIVE.equals(role.status)) {
                throw BusinessException.badRequest("角色已停用");
            }
        }
        return byId;
    }

    private SysRole requireRole(Long roleId) {
        if (roleId == null || roleId <= 0) {
            throw BusinessException.badRequest("角色 ID 不合法");
        }
        SysRole role = roleMapper.selectById(roleId);
        if (role == null || role.deleted != null && role.deleted == 1) {
            throw BusinessException.notFound("角色不存在");
        }
        return role;
    }

    private SysRole requireRoleForUpdate(Long roleId) {
        if (roleId == null || roleId <= 0) {
            throw BusinessException.badRequest("角色 ID 不合法");
        }
        SysRole role = roleMapper.findByIdForUpdate(roleId);
        if (role == null) {
            throw BusinessException.notFound("角色不存在");
        }
        return role;
    }

    private void requireMutableRole(SysRole role) {
        if (isBuiltInRole(role)) {
            throw BusinessException.badRequest("内置角色不可修改");
        }
    }

    private boolean isBuiltInRole(SysRole role) {
        return role != null && BUILT_IN_ROLE_KEYS.contains(role.roleKey);
    }

    private String normalizeRoleName(String roleName) {
        String normalized = trimToNull(roleName);
        if (normalized == null) {
            throw BusinessException.badRequest("角色名称不能为空");
        }
        if (normalized.length() > 64) {
            throw BusinessException.badRequest("角色名称不能超过64个字符");
        }
        return normalized;
    }

    private String normalizeRoleKey(String roleKey) {
        String normalized = trimToNull(roleKey);
        if (normalized == null) {
            throw BusinessException.badRequest("角色标识不能为空");
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (normalized.length() > 64 || !normalized.matches("[A-Z][A-Z0-9_]*")) {
            throw BusinessException.badRequest("角色标识只能包含字母、数字和下划线，且必须以字母开头");
        }
        return normalized;
    }

    private String normalizeRoleStatus(String status) {
        if (status == null || status.isBlank()) {
            throw BusinessException.badRequest("角色状态不能为空");
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!Constants.ACTIVE.equals(normalized) && !Constants.DISABLED.equals(normalized)) {
            throw BusinessException.badRequest("角色状态不合法");
        }
        return normalized;
    }

    private LinkedHashSet<Long> normalizePermissionMenuIds(List<Long> menuIds) {
        if (menuIds == null) {
            throw BusinessException.badRequest("权限菜单不能为空");
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long menuId : menuIds) {
            if (menuId == null || menuId <= 0) {
                throw BusinessException.badRequest("权限菜单 ID 不合法");
            }
            normalized.add(menuId);
        }
        return normalized;
    }

    private Map<Long, SysMenu> activePermissionMenusById() {
        return delegablePermissionMenus().stream()
            .collect(Collectors.toMap(menu -> menu.id, menu -> menu, (left, right) -> left));
    }

    private List<SysMenu> delegablePermissionMenus() {
        List<SysMenu> menus = menuMapper.findActivePermissionMenus();
        return menus == null
            ? List.of()
            : menus.stream().filter(menu -> !NON_DELEGABLE_PERMISSIONS.contains(menu.perms)).toList();
    }

    private LinkedHashSet<Long> normalizeVenueIds(List<Long> venueIds) {
        if (venueIds == null) {
            throw BusinessException.badRequest("场馆授权不能为空");
        }
        LinkedHashSet<Long> normalizedVenueIds = new LinkedHashSet<>();
        for (Long venueId : venueIds) {
            if (venueId == null || venueId <= 0) {
                throw BusinessException.badRequest("场馆 ID 不合法");
            }
            normalizedVenueIds.add(venueId);
        }
        return normalizedVenueIds;
    }

    private void requireSystemAdministrator() {
        CurrentUser currentUser = SecurityContext.currentUser();
        if (currentUser == null || !currentUser.hasRole(Constants.ROLE_SYSTEM_ADMIN)) {
            throw BusinessException.forbidden("仅系统管理员可维护场馆授权");
        }
    }

    private long userVersion(SysUser user) {
        return user.userVersion == null ? 0L : user.userVersion;
    }

    private String validateConfigValue(SysConfig config, String value) {
        if (value == null || value.isBlank()) {
            throw BusinessException.badRequest("配置值不能为空");
        }
        String normalizedValue = value.trim();
        String valueType = config.valueType == null ? "STRING" : config.valueType.trim().toUpperCase(Locale.ROOT);
        return switch (valueType) {
            case "STRING" -> normalizedValue;
            case "NUMBER" -> {
                int number;
                try {
                    number = Integer.parseInt(normalizedValue);
                } catch (NumberFormatException ex) {
                    throw BusinessException.badRequest("配置值必须是整数");
                }
                validateNumberConfigRange(config.configKey, number);
                yield normalizedValue;
            }
            case "BOOLEAN" -> {
                String lowerValue = normalizedValue.toLowerCase(Locale.ROOT);
                if (!"true".equals(lowerValue) && !"false".equals(lowerValue)) {
                    throw BusinessException.badRequest("配置值必须是 true 或 false");
                }
                yield lowerValue;
            }
            default -> throw BusinessException.badRequest("系统参数值类型不支持");
        };
    }

    private void validateNumberConfigRange(String configKey, int value) {
        if (configKey == null) {
            return;
        }
        switch (configKey) {
            case "reservation.advance_days" -> requireRange(configKey, value, 0, 365);
            case "reservation.free_cancel_hours" -> requireRange(configKey, value, 0, 24 * 31);
            case "payment.timeout_minutes" -> requireRange(configKey, value, 1, 24 * 60);
            case "reservation.max_slots_per_booking" -> requireRange(configKey, value, 1, 48);
            case "reservation.checkin.open_before_minutes", "reservation.checkin.close_after_minutes" ->
                requireRange(configKey, value, 0, 24 * 60);
            case "reservation.checkin.late_grace_minutes", "reservation.no_show.confirm_after_minutes" ->
                requireRange(configKey, value, 0, 24 * 60);
            case "reservation.lock_seconds" -> requireRange(configKey, value, 1, 24 * 60 * 60);
            default -> { }
        }
    }

    private void requireRange(String key, int value, int min, int max) {
        if (value < min || value > max) {
            throw BusinessException.badRequest("配置 " + key + " 必须在 " + min + " 到 " + max + " 之间");
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void saveDeletedUserSnapshot(SysUser user, List<String> roleKeys) {
        SysDeletedUserSnapshot snapshot = new SysDeletedUserSnapshot();
        snapshot.userId = user.id;
        snapshot.username = user.username;
        snapshot.nickname = user.nickname;
        snapshot.phone = user.phone;
        snapshot.email = user.email;
        snapshot.avatar = user.avatar;
        snapshot.status = user.status;
        snapshot.roleKeys = roleKeys == null || roleKeys.isEmpty() ? "" : String.join(",", roleKeys);
        snapshot.lastLoginTime = user.lastLoginTime;
        snapshot.userCreateTime = user.createTime;
        snapshot.userUpdateTime = user.updateTime;
        CurrentUser operator = SecurityContext.currentUser();
        if (operator != null) {
            snapshot.deletedBy = operator.id();
            snapshot.deletedByUsername = operator.username();
        }
        LocalDateTime deletedTime = LocalDateTime.now();
        snapshot.deletedTime = deletedTime;
        snapshot.deidentifyAfter = deletedTime.plusDays(365);
        deletedUserSnapshotMapper.insert(snapshot);
    }
}
