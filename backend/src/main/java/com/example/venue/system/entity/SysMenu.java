package com.example.venue.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("sys_menu")
public class SysMenu {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String menuName;
    public Long parentId;
    public String path;
    public String component;
    public String icon;
    public String perms;
    public String menuType;
    public Integer sortOrder;
    public String visible;
    public String status;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
