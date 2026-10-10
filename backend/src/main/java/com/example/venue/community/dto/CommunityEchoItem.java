package com.example.venue.community.dto;

import java.time.LocalDateTime;

/** 首页「城市里的回声」公开摘要：仅 PLATFORM_VISIBLE 小组的已发布帖子。 */
public class CommunityEchoItem {
    public Long postId;
    public Long groupId;
    public String groupName;
    public String title;
    public String excerpt;
    public String authorNickname;
    public LocalDateTime publishedAt;
}
