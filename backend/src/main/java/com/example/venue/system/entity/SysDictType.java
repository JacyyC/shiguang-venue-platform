package com.example.venue.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("sys_dict_type")
public class SysDictType {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String dictName;
    public String dictType;
    public String status;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
