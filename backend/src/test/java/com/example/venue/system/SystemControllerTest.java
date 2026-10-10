package com.example.venue.system;

import com.example.venue.common.Result;
import com.example.venue.system.controller.SystemController;
import com.example.venue.system.dto.CreateManagedRoleRequest;
import com.example.venue.system.dto.ReplaceRolePermissionsRequest;
import com.example.venue.system.dto.RoleOptionResponse;
import com.example.venue.system.dto.UpdateManagedRoleRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SystemControllerTest {
    @Test
    void roleAssignmentReadEndpointsRequireTheUserManagementPermission() throws Exception {
        Method catalog = SystemController.class.getMethod("assignableRoles");
        assertThat(catalog.getAnnotation(GetMapping.class).value()).containsExactly("/roles");
        assertThat(catalog.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('system:user:manage')");
        assertThat(catalog.getReturnType()).isEqualTo(Result.class);

        Method selection = SystemController.class.getMethod("userRoleIds", Long.class);
        assertThat(selection.getAnnotation(GetMapping.class).value()).containsExactly("/users/{id}/roles");
        assertThat(selection.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('system:user:manage')");
        assertThat(selection.getReturnType()).isEqualTo(Result.class);
        assertThat(catalog.getGenericReturnType().getTypeName()).contains(RoleOptionResponse.class.getSimpleName());
        assertThat(selection.getGenericReturnType().getTypeName()).contains(List.class.getSimpleName());
    }

    @Test
    void dynamicRoleManagementEndpointsUseOnlyTheRoleManagementPermission() throws Exception {
        Method roles = SystemController.class.getMethod("managedRoles");
        assertThat(roles.getAnnotation(GetMapping.class).value())
            .containsExactly("/role-management/roles");

        Method permissions = SystemController.class.getMethod("permissionMenus");
        assertThat(permissions.getAnnotation(GetMapping.class).value())
            .containsExactly("/role-management/permissions");

        Method selection = SystemController.class.getMethod("rolePermissions", Long.class);
        assertThat(selection.getAnnotation(GetMapping.class).value())
            .containsExactly("/role-management/roles/{id}/permissions");

        Method create = SystemController.class.getMethod("createRole", CreateManagedRoleRequest.class);
        assertThat(create.getAnnotation(PostMapping.class).value())
            .containsExactly("/role-management/roles");

        Method update = SystemController.class.getMethod("updateRole", Long.class, UpdateManagedRoleRequest.class);
        assertThat(update.getAnnotation(PutMapping.class).value())
            .containsExactly("/role-management/roles/{id}");

        Method replace = SystemController.class.getMethod(
            "replaceRolePermissions", Long.class, ReplaceRolePermissionsRequest.class);
        assertThat(replace.getAnnotation(PutMapping.class).value())
            .containsExactly("/role-management/roles/{id}/permissions");

        assertThat(List.of(roles, permissions, selection, create, update, replace))
            .allSatisfy(method -> assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('system:role:manage')"));
    }
}
