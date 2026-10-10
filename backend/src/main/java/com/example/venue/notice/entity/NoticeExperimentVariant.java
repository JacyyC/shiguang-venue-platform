package com.example.venue.notice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("notice_experiment_variant")
public class NoticeExperimentVariant {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long experimentId;
    public String variantCode;
    public String title;
    public String content;
    public Integer allocationPercent;
    public String metadataJson;
    public LocalDateTime createTime;
}
