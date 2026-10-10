package com.example.venue.community.service;

import com.example.venue.community.dto.CommunityEchoItem;
import com.example.venue.community.dto.CommunityQueryRows;
import com.example.venue.community.mapper.CommunityPostMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 首页社区摘要（只读）：复用 searchVisible 的可见性口径，但只透出 PLATFORM_VISIBLE 小组帖子，
 * 成员可见内容不进入公开推荐。拉取有界（最多 25 行），按入参 limit（1-10）截取。
 */
@Service
public class CommunityEchoService {
    private static final int SCAN_LIMIT = 25;

    private final CommunityPostMapper postMapper;

    public CommunityEchoService(CommunityPostMapper postMapper) {
        this.postMapper = postMapper;
    }

    @Transactional(readOnly = true)
    public List<CommunityEchoItem> echo(Long userId, int limit) {
        int bounded = Math.min(Math.max(limit, 1), 10);
        List<CommunityQueryRows.PostRow> rows = postMapper.searchVisible(
            userId, null, null, null, null, true, null, SCAN_LIMIT);
        return rows.stream()
            .filter(row -> "PLATFORM_VISIBLE".equals(row.groupVisibility))
            .limit(bounded)
            .map(this::toItem)
            .toList();
    }

    private CommunityEchoItem toItem(CommunityQueryRows.PostRow row) {
        CommunityEchoItem item = new CommunityEchoItem();
        item.postId = row.id;
        item.groupId = row.groupId;
        item.groupName = row.groupName;
        item.title = row.title;
        item.excerpt = excerpt(row.contentText);
        item.authorNickname = row.authorNickname;
        item.publishedAt = row.publishedAt;
        return item;
    }

    private String excerpt(String content) {
        if (content == null) return "";
        String flattened = content.replaceAll("\\s+", " ").trim();
        return flattened.length() <= 120 ? flattened : flattened.substring(0, 120);
    }
}
