package com.example.venue.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.system.entity.SysRoleMenu;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysRoleMenuMapper extends BaseMapper<SysRoleMenu> {
    @Delete("delete from sys_role_menu where role_id = #{roleId}")
    int deleteByRoleId(@Param("roleId") Long roleId);

    @Select("select menu_id from sys_role_menu where role_id = #{roleId} order by menu_id")
    List<Long> findMenuIdsByRoleId(@Param("roleId") Long roleId);
}
