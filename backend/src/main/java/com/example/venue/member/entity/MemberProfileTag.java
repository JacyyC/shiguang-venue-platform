package com.example.venue.member.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("member_profile_tag")
public class MemberProfileTag {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String tagCode;
    public String tagLabel;
    public String sourceType;
    public BigDecimal confidence;
    public String evidenceRef;
    public String metadataJson;
    public LocalDateTime generatedAt;
    public String status;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
}
