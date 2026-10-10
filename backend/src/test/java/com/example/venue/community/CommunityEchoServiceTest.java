package com.example.venue.community;

import com.example.venue.community.dto.CommunityQueryRows;
import com.example.venue.community.mapper.CommunityPostMapper;
import com.example.venue.community.service.CommunityEchoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommunityEchoServiceTest {
    @Mock
    private CommunityPostMapper postMapper;

    private CommunityEchoService echoService;

    @BeforeEach
    void setUp() {
        echoService = new CommunityEchoService(postMapper);
    }

    @Test
    void onlyPlatformVisiblePostsAreRecommended() {
        when(postMapper.searchVisible(anyLong(), isNull(), isNull(), isNull(), isNull(), eq(true), isNull(), eq(25)))
            .thenReturn(List.of(
                postRow(1L, "PLATFORM_VISIBLE", "窗边共学", "一个人坐两小时，你会去哪里？"),
                postRow(2L, "MEMBERS_ONLY", "内部小组", "成员可见的帖子"),
                postRow(3L, "PLATFORM_VISIBLE", "周末球场", "周三下班后有人一起打球吗？")));

        List<CommunityEchoItemDto> items = CommunityEchoItemDto.of(echoService.echo(7L, 5));

        assertThat(items).extracting(item -> item.title)
            .containsExactly("一个人坐两小时，你会去哪里？", "周三下班后有人一起打球吗？");
        assertThat(items.get(0).groupName).isEqualTo("窗边共学");
    }

    @Test
    void limitIsBoundedToOneToTen() {
        when(postMapper.searchVisible(anyLong(), isNull(), isNull(), isNull(), isNull(), eq(true), isNull(), eq(25)))
            .thenReturn(IntStream.rangeClosed(1, 30)
                .mapToObj(i -> postRow((long) i, "PLATFORM_VISIBLE", "小组", "帖子 " + i))
                .toList());

        assertThat(echoService.echo(7L, 50)).hasSize(10);
        assertThat(echoService.echo(7L, 0)).hasSize(1);
    }

    @Test
    void excerptIsFlattenedAndTruncated() {
        CommunityQueryRows.PostRow longRow = postRow(2L, "PLATFORM_VISIBLE", "小组", "长帖子");
        longRow.contentText = "字".repeat(300) + "\n换行";
        when(postMapper.searchVisible(anyLong(), isNull(), isNull(), isNull(), isNull(), eq(true), isNull(), eq(25)))
            .thenReturn(List.of(longRow));

        String excerpt = echoService.echo(7L, 5).get(0).excerpt;
        assertThat(excerpt).hasSize(120);
        assertThat(excerpt).doesNotContain("\n");
    }

    private CommunityQueryRows.PostRow postRow(Long id, String visibility, String groupName, String title) {
        CommunityQueryRows.PostRow row = new CommunityQueryRows.PostRow();
        row.id = id;
        row.groupId = 100L + id;
        row.groupName = groupName;
        row.groupVisibility = visibility;
        row.authorNickname = "作者" + id;
        row.title = title;
        row.contentText = title == null ? null : "“" + title + "”的正文。";
        row.status = "PUBLISHED";
        return row;
    }

    /** 仅用于测试断言的轻量投影，避免测试依赖主 DTO 字段访问方式。 */
    record CommunityEchoItemDto(Long postId, String title, String groupName, String excerpt) {
        static List<CommunityEchoItemDto> of(List<com.example.venue.community.dto.CommunityEchoItem> items) {
            return items.stream().map(item -> new CommunityEchoItemDto(item.postId, item.title, item.groupName, item.excerpt)).toList();
        }
    }
}
