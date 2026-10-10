package com.example.venue.notice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("notice_experiment")
public class NoticeExperiment {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String experimentNo;
    public String name;
    public String status;
    public String targetMetric;
    public String sampleScope;
    public LocalDateTime startTime;
    public LocalDateTime endTime;
    public Long ownerId;
    public String metadataJson;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
