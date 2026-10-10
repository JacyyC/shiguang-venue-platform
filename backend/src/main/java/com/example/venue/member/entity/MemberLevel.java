package com.example.venue.member.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("member_level")
public class MemberLevel {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String levelCode;
    public String levelName;
    public Integer growthThreshold;
    public BigDecimal discountRate;
    public String benefitText;
    public String ruleVersion;
    public String status;
    public Integer sortOrder;
    public LocalDateTime effectiveTime;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
