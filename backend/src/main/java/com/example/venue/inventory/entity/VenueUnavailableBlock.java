package com.example.venue.inventory.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("venue_unavailable_block")
public class VenueUnavailableBlock {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long venueId;
    public Long spaceId;
    public String blockType;
    public String reasonCode;
    public String reasonText;
    public LocalDateTime startTime;
    public LocalDateTime endTime;
    public Integer bufferBeforeMinutes;
    public Integer bufferAfterMinutes;
    public String sourceType;
    public String providerEnv;
    public String providerName;
    public String externalRef;
    public String idempotencyKey;
    public String relatedEntityType;
    public Long relatedEntityId;
    public String status;
    public Long createdBy;
    public Long updatedBy;
    public String metadataJson;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    public Integer activeFlag;
}
