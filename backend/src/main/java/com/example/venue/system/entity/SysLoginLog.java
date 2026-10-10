package com.example.venue.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("sys_login_log")
public class SysLoginLog {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String username;
    public String ip;
    public String userAgent;
    public String status;
    public String message;
    public LocalDateTime createTime;
}
