package com.example.venue.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("sys_operation_log")
public class SysOperationLog {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String username;
    public String module;
    public String action;
    public String method;
    public String requestUri;
    public String params;
    public String ip;
    public String resultStatus;
    public String errorMessage;
    public LocalDateTime createTime;
}
