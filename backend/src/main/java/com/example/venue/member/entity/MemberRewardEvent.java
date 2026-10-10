package com.example.venue.member.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("member_reward_event")
public class MemberRewardEvent {
    @TableId(type = IdType.AUTO) public Long id;
    public String eventNo;
    public String eventKey;
    public String eventType;
    public Long redemptionId;
    public Long catalogItemId;
    public Long catalogVersionId;
    public Long actorUserId;
    public String sourceType;
    public String reasonCode;
    public String reasonText;
    public String fromStatus;
    public String toStatus;
    public String resultStatus;
    public String metadataJson;
    public LocalDateTime occurredAt;
    public LocalDateTime createTime;
}
