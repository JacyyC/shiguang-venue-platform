package com.example.venue.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.system.entity.SysUserRole;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {
    @Delete("delete from sys_user_role where user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);

    @Select("""
        select ur.role_id
        from sys_user_role ur
        join sys_role r on r.id = ur.role_id
        where ur.user_id = #{userId}
          and r.deleted = 0
          and r.status = 'ACTIVE'
        order by r.id
        """)
    List<Long> findActiveRoleIdsByUserId(@Param("userId") Long userId);
}
