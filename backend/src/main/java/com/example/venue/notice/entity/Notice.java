package com.example.venue.notice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("notice")
public class Notice {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String title;
    public String content;
    public String type;
    public Long targetVenueId;
    public String status;
    public Long publisherId;
    public LocalDateTime publishTime;
    public Long deliveryBatchId;
    public String targetRuleJson;
    public Long experimentId;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
