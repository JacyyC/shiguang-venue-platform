package com.example.venue.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.system.entity.SysMenu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysMenuMapper extends BaseMapper<SysMenu> {
    @Select("""
        select distinct m.*
        from sys_menu m
        join sys_role_menu rm on rm.menu_id = m.id
        join sys_user_role ur on ur.role_id = rm.role_id
        join sys_role r on r.id = rm.role_id
        where ur.user_id = #{userId}
          and r.deleted = 0
          and r.status = 'ACTIVE'
          and m.deleted = 0
          and m.status = 'ACTIVE'
          and m.visible = 'Y'
        order by m.parent_id, m.sort_order, m.id
        """)
    List<SysMenu> findMenusByUserId(@Param("userId") Long userId);

    @Select("""
        select *
        from sys_menu
        where deleted = 0
          and status = 'ACTIVE'
          and perms is not null
          and perms <> ''
        order by parent_id, sort_order, id
        """)
    List<SysMenu> findActivePermissionMenus();
}
