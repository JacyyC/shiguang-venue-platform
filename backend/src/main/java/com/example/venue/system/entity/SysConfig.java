package com.example.venue.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("sys_config")
public class SysConfig {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String configKey;
    public String configValue;
    public String configName;
    public String description;
    public String valueType;
    public String editable;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
