package com.example.venue.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("sys_role_menu")
public class SysRoleMenu {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long roleId;
    public Long menuId;
}
