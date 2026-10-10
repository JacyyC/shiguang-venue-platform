package com.example.venue.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.community.dto.CommunityQueryRows;
import com.example.venue.community.entity.CommunityPost;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CommunityPostMapper extends BaseMapper<CommunityPost> {
    @Select("select * from community_post where id = #{id} for update")
    CommunityPost selectByIdForUpdate(@Param("id") Long id);

    @Select("""
        select * from community_post
        where author_user_id = #{authorUserId} and idempotency_key = #{idempotencyKey}
        limit 1
        """)
    CommunityPost findByAuthorAndIdempotency(@Param("authorUserId") Long authorUserId,
                                              @Param("idempotencyKey") String idempotencyKey);

    @Select("""
        select * from community_post
        where author_user_id = #{authorUserId} and idempotency_key = #{idempotencyKey}
        for update
        """)
    CommunityPost findByAuthorAndIdempotencyForUpdate(@Param("authorUserId") Long authorUserId,
                                                       @Param("idempotencyKey") String idempotencyKey);

    @Select("""
        <script>
        select p.id, p.post_no, p.group_id, g.name as group_name, g.visibility as group_visibility,
               g.category_code as group_category,
               case when author.id is null then null else p.author_user_id end as author_user_id,
               author.nickname as author_nickname, author.avatar as author_avatar,
               p.title, p.content_text, p.status, p.version_no,
               p.published_at, p.create_time, p.update_time
        from community_post p
        join community_group g on g.id = p.group_id and g.status = 'ACTIVE'
        left join sys_user author on author.id = p.author_user_id and author.deleted = 0
        where p.status = 'PUBLISHED'
          and (g.visibility = 'PLATFORM_VISIBLE' or exists (
            select 1 from community_group_member mine
            where mine.group_id = g.id
              and mine.user_id = #{userId}
              and mine.status = 'ACTIVE'
          ))
          and not exists (
            select 1 from community_user_block outgoing
            where outgoing.blocker_user_id = #{userId}
              and outgoing.status = 'ACTIVE'
              and outgoing.blocked_user_id in (p.author_user_id, g.owner_user_id)
          )
          and not exists (
            select 1 from community_user_block incoming
            where incoming.blocked_user_id = #{userId}
              and incoming.status = 'ACTIVE'
              and incoming.blocker_user_id in (p.author_user_id, g.owner_user_id)
          )
        <if test="groupId != null">
          and p.group_id = #{groupId}
        </if>
        <if test="authorId != null">
          and p.author_user_id = #{authorId}
        </if>
        <if test="titlePrefix != null and titlePrefix != ''">
          and p.title like concat(#{titlePrefix}, '%')
        </if>
        <if test="category != null and category != ''">
          <choose>
            <when test='category == "OTHER"'>
              and g.category_code is null
            </when>
            <otherwise>
              and g.category_code = #{category}
            </otherwise>
          </choose>
        </if>
        <if test="platformOnly">
          and g.visibility = 'PLATFORM_VISIBLE'
        </if>
        <if test="cursorId != null">
          and p.id &lt; #{cursorId}
        </if>
        order by p.id desc
        limit #{limit}
        </script>
        """)
    List<CommunityQueryRows.PostRow> searchVisible(@Param("userId") Long userId,
                                                    @Param("groupId") Long groupId,
                                                    @Param("authorId") Long authorId,
                                                    @Param("titlePrefix") String titlePrefix,
                                                    @Param("category") String category,
                                                    @Param("platformOnly") boolean platformOnly,
                                                    @Param("cursorId") Long cursorId,
                                                    @Param("limit") int limit);

    /**
     * 帖子详情的受控聚合读取：列与 searchVisible 相同（PostRow 白名单），
     * 可见性谓词与 CommunityAccessService#requireVisiblePost 逐项对齐；
     * 任一条件不满足返回 null，由服务层统一抛出不可枚举的 404。
     */
    @Select("""
        select p.id, p.post_no, p.group_id, g.name as group_name, g.visibility as group_visibility,
               g.category_code as group_category,
               case when author.id is null then null else p.author_user_id end as author_user_id,
               author.nickname as author_nickname, author.avatar as author_avatar,
               p.title, p.content_text, p.status, p.version_no,
               p.published_at, p.create_time, p.update_time
        from community_post p
        join community_group g on g.id = p.group_id and g.status = 'ACTIVE'
        left join sys_user author on author.id = p.author_user_id and author.deleted = 0
        where p.id = #{postId}
          and p.status = 'PUBLISHED'
          and (g.visibility = 'PLATFORM_VISIBLE' or exists (
            select 1 from community_group_member mine
            where mine.group_id = g.id
              and mine.user_id = #{userId}
              and mine.status = 'ACTIVE'
          ))
          and not exists (
            select 1 from community_user_block outgoing
            where outgoing.blocker_user_id = #{userId}
              and outgoing.status = 'ACTIVE'
              and outgoing.blocked_user_id in (p.author_user_id, g.owner_user_id)
          )
          and not exists (
            select 1 from community_user_block incoming
            where incoming.blocked_user_id = #{userId}
              and incoming.status = 'ACTIVE'
              and incoming.blocker_user_id in (p.author_user_id, g.owner_user_id)
          )
        """)
    CommunityQueryRows.PostRow findVisibleDetail(@Param("userId") Long userId,
                                                  @Param("postId") Long postId);

    /**
     * 组内治理内容列表（社区 V1 G8）：仅服务小组 OWNER/ADMIN 的处置面，不按阅读者做
     * 成员可见性/双向屏蔽过滤；权限由服务层 requireGroupManager 收口。
     * status 取值 ALL（PUBLISHED+HIDDEN）或 HIDDEN；返回与列表一致的 PostRow 白名单列。
     */
    @Select("""
        <script>
        select p.id, p.post_no, p.group_id, g.name as group_name, g.visibility as group_visibility,
               g.category_code as group_category,
               case when author.id is null then null else p.author_user_id end as author_user_id,
               author.nickname as author_nickname, author.avatar as author_avatar,
               p.title, p.content_text, p.status, p.version_no,
               p.published_at, p.create_time, p.update_time
        from community_post p
        join community_group g on g.id = p.group_id and g.status = 'ACTIVE'
        left join sys_user author on author.id = p.author_user_id and author.deleted = 0
        where p.group_id = #{groupId}
        <choose>
          <when test='status == "HIDDEN"'>
            and p.status = 'HIDDEN'
          </when>
          <otherwise>
            and p.status in ('PUBLISHED', 'HIDDEN')
          </otherwise>
        </choose>
        <if test="cursorId != null">
          and p.id &lt; #{cursorId}
        </if>
        order by p.id desc
        limit #{limit}
        </script>
        """)
    List<CommunityQueryRows.PostRow> listForModeration(@Param("groupId") Long groupId,
                                                        @Param("status") String status,
                                                        @Param("cursorId") Long cursorId,
                                                        @Param("limit") int limit);
}
