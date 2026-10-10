package com.example.venue.security;

import com.example.venue.system.mapper.SysMenuMapper;
import com.example.venue.user.mapper.SysUserMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class RoleAuthorizationMapperTest {
    @Test
    void inactiveOrDeletedRolesCannotProduceFineGrainedPermissions() throws Exception {
        String sql = selectSql(SysUserMapper.class.getMethod("findPermissions", Long.class));

        assertThat(sql).contains(
            "join sys_role r on r.id = rm.role_id",
            "r.deleted = 0",
            "r.status = 'active'"
        );
    }

    @Test
    void inactiveOrDeletedRolesCannotProduceMenus() throws Exception {
        String sql = selectSql(SysMenuMapper.class.getMethod("findMenusByUserId", Long.class));

        assertThat(sql).contains(
            "join sys_role r on r.id = rm.role_id",
            "r.deleted = 0",
            "r.status = 'active'"
        );
    }

    private String selectSql(Method method) {
        Select select = method.getAnnotation(Select.class);
        return String.join(" ", select.value()).toLowerCase(Locale.ROOT);
    }
}
