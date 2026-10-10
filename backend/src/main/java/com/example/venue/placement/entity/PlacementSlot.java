package com.example.venue.placement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("placement_slot")
public class PlacementSlot {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String slotCode;
    public String slotName;
    public String status;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
