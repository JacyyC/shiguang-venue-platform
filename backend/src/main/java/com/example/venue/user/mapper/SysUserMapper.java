package com.example.venue.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.user.entity.SysUser;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
    @Select("select * from sys_user where username = #{username} and deleted = 0 limit 1")
    SysUser findByUsername(@Param("username") String username);

    @Delete("delete from sys_user where id = #{userId}")
    int physicallyDeleteById(@Param("userId") Long userId);

    @Select("""
        select r.role_key
        from sys_role r
        join sys_user_role ur on ur.role_id = r.id
        where ur.user_id = #{userId} and r.deleted = 0 and r.status = 'ACTIVE'
        """)
    List<String> findRoleKeys(@Param("userId") Long userId);

    @Select("""
        select count(distinct u.id)
        from sys_user u
        join sys_user_role ur on ur.user_id = u.id
        join sys_role r on r.id = ur.role_id
        where u.deleted = 0
          and u.status = 'ACTIVE'
          and r.deleted = 0
          and r.status = 'ACTIVE'
          and r.role_key = #{roleKey}
        """)
    long countActiveUsersByRoleKey(@Param("roleKey") String roleKey);

    @Select("""
        select distinct u.*
        from sys_user u
        join sys_user_role ur on ur.user_id = u.id
        join sys_role r on r.id = ur.role_id
        where u.deleted = 0
          and u.status = 'ACTIVE'
          and r.deleted = 0
          and r.status = 'ACTIVE'
          and r.role_key = #{roleKey}
        order by u.id asc
        """)
    List<SysUser> findActiveUsersByRoleKey(@Param("roleKey") String roleKey);

    @Select("""
        <script>
        select distinct u.*
        from sys_user u
        join sys_user_role ur on ur.user_id = u.id
        join sys_role r on r.id = ur.role_id
        where r.deleted = 0
          and r.status = 'ACTIVE'
          and r.role_key = #{roleKey}
          and u.id in
          <foreach collection="userIds" item="userId" open="(" separator="," close=")">
            #{userId}
          </foreach>
        </script>
        """)
    List<SysUser> findActiveUsersByIdsAndRoleKey(@Param("userIds") Collection<Long> userIds,
                                                 @Param("roleKey") String roleKey);

    @Select("""
        select distinct m.perms
        from sys_menu m
        join sys_role_menu rm on rm.menu_id = m.id
        join sys_user_role ur on ur.role_id = rm.role_id
        join sys_role r on r.id = rm.role_id
        where ur.user_id = #{userId}
          and r.deleted = 0
          and r.status = 'ACTIVE'
          and m.deleted = 0
          and m.status = 'ACTIVE'
          and m.perms is not null
          and m.perms <> ''
        """)
    List<String> findPermissions(@Param("userId") Long userId);

    @Update("""
        update sys_user
        set last_login_time = #{lastLoginTime},
            update_time = #{updateTime}
        where id = #{userId}
          and deleted = 0
          and status = 'ACTIVE'
        """)
    int updateLastLoginTime(@Param("userId") Long userId,
                            @Param("lastLoginTime") java.time.LocalDateTime lastLoginTime,
                            @Param("updateTime") java.time.LocalDateTime updateTime);

    @Update("""
        update sys_user
        set nickname = #{nickname},
            phone = #{phone},
            email = #{email},
            avatar = #{avatar},
            update_time = #{updateTime},
            user_version = user_version + 1
        where id = #{userId}
          and deleted = 0
          and status = 'ACTIVE'
          and user_version = #{expectedVersion}
        """)
    int updateProfile(@Param("userId") Long userId,
                      @Param("nickname") String nickname,
                      @Param("phone") String phone,
                      @Param("email") String email,
                      @Param("avatar") String avatar,
                      @Param("updateTime") java.time.LocalDateTime updateTime,
                      @Param("expectedVersion") long expectedVersion);

    @Update("""
        update sys_user
        set username = #{username},
            nickname = #{nickname},
            phone = #{phone},
            email = #{email},
            status = #{status},
            update_time = #{updateTime},
            user_version = user_version + 1
        where id = #{userId}
          and deleted = 0
          and user_version = #{expectedVersion}
        """)
    int updateSystemUser(@Param("userId") Long userId,
                         @Param("username") String username,
                         @Param("nickname") String nickname,
                         @Param("phone") String phone,
                         @Param("email") String email,
                         @Param("status") String status,
                         @Param("updateTime") java.time.LocalDateTime updateTime,
                         @Param("expectedVersion") long expectedVersion);

    @Update("""
        update sys_user
        set status = #{status},
            update_time = #{updateTime},
            user_version = user_version + 1
        where id = #{userId}
          and deleted = 0
          and user_version = #{expectedVersion}
        """)
    int updateUserStatus(@Param("userId") Long userId,
                         @Param("status") String status,
                         @Param("updateTime") java.time.LocalDateTime updateTime,
                         @Param("expectedVersion") long expectedVersion);

    @Update("""
        update sys_user
        set role_id = #{roleId},
            update_time = #{updateTime},
            user_version = user_version + 1
        where id = #{userId}
          and deleted = 0
          and user_version = #{expectedVersion}
        """)
    int updateRoleProjection(@Param("userId") Long userId,
                             @Param("roleId") Long roleId,
                             @Param("updateTime") java.time.LocalDateTime updateTime,
                             @Param("expectedVersion") long expectedVersion);

    @Update("""
        update sys_user
        set password = #{password},
            credential_version = credential_version + 1,
            update_time = #{updateTime},
            user_version = user_version + 1
        where id = #{userId}
          and deleted = 0
          and status = 'ACTIVE'
          and password = #{expectedPassword}
          and user_version = #{expectedVersion}
        """)
    int changePassword(@Param("userId") Long userId,
                       @Param("password") String password,
                       @Param("expectedPassword") String expectedPassword,
                       @Param("updateTime") java.time.LocalDateTime updateTime,
                       @Param("expectedVersion") long expectedVersion);

    @Update("""
        update sys_user
        set password = #{password},
            credential_version = credential_version + 1,
            update_time = #{updateTime},
            user_version = user_version + 1
        where id = #{userId}
          and deleted = 0
          and user_version = #{expectedVersion}
        """)
    int resetPassword(@Param("userId") Long userId,
                      @Param("password") String password,
                      @Param("updateTime") java.time.LocalDateTime updateTime,
                      @Param("expectedVersion") long expectedVersion);
}
