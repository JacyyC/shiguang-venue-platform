package com.example.venue.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("sys_role")
public class SysRole {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String roleName;
    public String roleKey;
    public String dataScope;
    public String status;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
