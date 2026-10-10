package com.example.venue.member.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("member_reward_catalog_item")
public class MemberRewardCatalogItem {
    @TableId(type = IdType.AUTO) public Long id;
    public String itemCode;
    public String itemName;
    public String descriptionText;
    public String status;
    public Long createdBy;
    public Long updatedBy;
    public LocalDateTime createTime;
    public LocalDateTime updateTime;
    public Integer deleted;
}
