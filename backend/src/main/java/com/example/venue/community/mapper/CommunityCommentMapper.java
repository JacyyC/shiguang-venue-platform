package com.example.venue.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.community.dto.CommunityQueryRows;
import com.example.venue.community.entity.CommunityComment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CommunityCommentMapper extends BaseMapper<CommunityComment> {
    @Select("select * from community_comment where id = #{id} for update")
    CommunityComment selectByIdForUpdate(@Param("id") Long id);

    @Select("""
        select * from community_comment
        where author_user_id = #{authorUserId} and idempotency_key = #{idempotencyKey}
        limit 1
        """)
    CommunityComment findByAuthorAndIdempotency(@Param("authorUserId") Long authorUserId,
                                                 @Param("idempotencyKey") String idempotencyKey);

    @Select("""
        select * from community_comment
        where author_user_id = #{authorUserId} and idempotency_key = #{idempotencyKey}
        for update
        """)
    CommunityComment findByAuthorAndIdempotencyForUpdate(@Param("authorUserId") Long authorUserId,
                                                          @Param("idempotencyKey") String idempotencyKey);

    @Select("""
        <script>
        select c.id, c.comment_no, c.post_id, c.group_id,
               case when author.id is null then null else c.author_user_id end as author_user_id,
               author.nickname as author_nickname, author.avatar as author_avatar,
               c.content_text, c.status, c.version_no,
               c.published_at, c.create_time, c.update_time
        from community_comment c
        join community_post p on p.id = c.post_id and p.status = 'PUBLISHED'
        join community_group g on g.id = c.group_id and g.status = 'ACTIVE'
        left join community_group_member mine
          on mine.group_id = g.id and mine.user_id = #{userId}
        left join sys_user author on author.id = c.author_user_id and author.deleted = 0
        where c.post_id = #{postId}
          and c.status = 'PUBLISHED'
          and (g.visibility = 'PLATFORM_VISIBLE' or mine.status = 'ACTIVE')
          and not exists (
            select 1 from community_user_block b
            where b.status = 'ACTIVE'
              and ((b.blocker_user_id = #{userId} and b.blocked_user_id = c.author_user_id)
                or (b.blocker_user_id = c.author_user_id and b.blocked_user_id = #{userId}))
          )
        <if test="cursorId != null">
          and c.id &lt; #{cursorId}
        </if>
        order by c.id desc
        limit #{limit}
        </script>
        """)
    List<CommunityQueryRows.CommentRow> listVisible(@Param("userId") Long userId,
                                                     @Param("postId") Long postId,
                                                     @Param("cursorId") Long cursorId,
                                                     @Param("limit") int limit);
}
