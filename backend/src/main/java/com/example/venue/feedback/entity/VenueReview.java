package com.example.venue.feedback.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

@TableName("venue_review")
public class VenueReview {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String reviewNo;
    public Long userId;
    public Long venueId;
    public Long spaceId;
    public Long reservationId;
    public Long orderId;
    public Long lifecycleId;
    public Integer overallRating;
    public Integer venueRating;
    public Integer spaceRating;
    public Integer serviceRating;
    public String tagsJson;
    public String contentText;
    public String contentSanitized;
    // A26-P0-04：原文落库列，仅审计/管理员可见，前端响应不返回（@JsonIgnore + DTO 不映射）。
    @JsonIgnore
    public String contentRaw;
    public Integer sensitiveFlag;
    public String status;
    public LocalDateTime submittedAt;
    public LocalDateTime publishedAt;
    public String hiddenReason;
    public String sourceType;
    public String metadataJson;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
