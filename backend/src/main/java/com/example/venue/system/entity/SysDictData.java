package com.example.venue.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("sys_dict_data")
public class SysDictData {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long dictTypeId;
    public String dictType;
    public String label;
    public String value;
    public Integer sortOrder;
    public String status;
}
