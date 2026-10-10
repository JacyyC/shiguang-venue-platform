package com.example.venue.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("sys_user_role")
public class SysUserRole {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long roleId;
}
