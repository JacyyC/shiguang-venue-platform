package com.example.venue.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.system.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {
    @Select("select * from sys_role where role_key = #{roleKey} limit 1 for update")
    SysRole findByRoleKeyForUpdate(@Param("roleKey") String roleKey);

    @Select("select * from sys_role where id = #{roleId} and deleted = 0 limit 1 for update")
    SysRole findByIdForUpdate(@Param("roleId") Long roleId);
}
