package com.example.venue.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.community.dto.CommunityQueryRows;
import com.example.venue.community.entity.CommunityGroup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CommunityGroupMapper extends BaseMapper<CommunityGroup> {
    @Select("select * from community_group where id = #{id} for update")
    CommunityGroup selectByIdForUpdate(@Param("id") Long id);

    @Select("""
        select * from community_group
        where owner_user_id = #{ownerUserId} and idempotency_key = #{idempotencyKey}
        limit 1
        """)
    CommunityGroup findByOwnerAndIdempotency(@Param("ownerUserId") Long ownerUserId,
                                              @Param("idempotencyKey") String idempotencyKey);

    @Select("""
        select * from community_group
        where owner_user_id = #{ownerUserId} and idempotency_key = #{idempotencyKey}
        for update
        """)
    CommunityGroup findByOwnerAndIdempotencyForUpdate(@Param("ownerUserId") Long ownerUserId,
                                                       @Param("idempotencyKey") String idempotencyKey);

    @Select("""
        select max(reason_retain_until) from community_report
        where target_type = 'GROUP' and target_id = #{groupId}
          and status in ('RESOLVED','DISMISSED')
        """)
    LocalDateTime findMaximumReasonRetention(@Param("groupId") Long groupId);

    @Select("""
        <script>
        select g.id, g.group_no, g.name, g.description,
               case when owner.id is null then null else g.owner_user_id end as owner_user_id,
               owner.nickname as owner_nickname, owner.avatar as owner_avatar,
               g.visibility, g.join_policy, g.category_code, g.status,
               mine.member_role as current_member_role, mine.status as current_member_status,
               (select count(*) from community_group_member members
                 where members.group_id = g.id and members.status = 'ACTIVE') as member_count,
               g.version_no, g.create_time, g.update_time
        from community_group g
        left join sys_user owner on owner.id = g.owner_user_id and owner.deleted = 0
        left join community_group_member mine
          on mine.group_id = g.id and mine.user_id = #{userId}
        where g.status = 'ACTIVE'
          and not exists (
            select 1 from community_user_block b
            where b.status = 'ACTIVE'
              and ((b.blocker_user_id = #{userId} and b.blocked_user_id = g.owner_user_id)
                or (b.blocker_user_id = g.owner_user_id and b.blocked_user_id = #{userId}))
          )
        <if test="namePrefix != null and namePrefix != ''">
          and g.name like concat(#{namePrefix}, '%')
        </if>
        <if test="categoryCode != null and categoryCode != ''">
          <choose>
            <when test='categoryCode == "OTHER"'>
              and g.category_code is null
            </when>
            <otherwise>
              and g.category_code = #{categoryCode}
            </otherwise>
          </choose>
        </if>
        <if test="cursorId != null">
          and g.id &lt; #{cursorId}
        </if>
        order by g.id desc
        limit #{limit}
        </script>
        """)
    List<CommunityQueryRows.GroupRow> searchVisible(@Param("userId") Long userId,
                                                     @Param("namePrefix") String namePrefix,
                                                     @Param("categoryCode") String categoryCode,
                                                     @Param("cursorId") Long cursorId,
                                                     @Param("limit") int limit);

    @Select("""
        <script>
        select g.id, g.group_no, g.name, g.description,
               case when owner.id is null then null else g.owner_user_id end as owner_user_id,
               owner.nickname as owner_nickname, owner.avatar as owner_avatar,
               g.visibility, g.join_policy, g.category_code, g.status,
               mine.member_role as current_member_role, mine.status as current_member_status,
               (select count(*) from community_group_member members
                 where members.group_id = g.id and members.status = 'ACTIVE') as member_count,
               g.version_no, g.create_time, g.update_time
        from community_group g
        join community_group_member mine
          on mine.group_id = g.id and mine.user_id = #{userId}
        left join sys_user owner on owner.id = g.owner_user_id and owner.deleted = 0
        where g.status = 'ACTIVE'
        <choose>
          <when test='memberStatus == "PENDING"'>
            and mine.status = 'PENDING'
          </when>
          <when test='memberStatus == "ANY"'>
            and mine.status in ('ACTIVE', 'PENDING')
          </when>
          <otherwise>
            and mine.status = 'ACTIVE'
          </otherwise>
        </choose>
        <if test="namePrefix != null and namePrefix != ''">
          and g.name like concat(#{namePrefix}, '%')
        </if>
        <if test="categoryCode != null and categoryCode != ''">
          <choose>
            <when test='categoryCode == "OTHER"'>
              and g.category_code is null
            </when>
            <otherwise>
              and g.category_code = #{categoryCode}
            </otherwise>
          </choose>
        </if>
        <if test="cursorId != null">
          and g.id &lt; #{cursorId}
        </if>
        order by g.id desc
        limit #{limit}
        </script>
        """)
    List<CommunityQueryRows.GroupRow> searchMine(@Param("userId") Long userId,
                                                  @Param("namePrefix") String namePrefix,
                                                  @Param("categoryCode") String categoryCode,
                                                  @Param("memberStatus") String memberStatus,
                                                  @Param("cursorId") Long cursorId,
                                                  @Param("limit") int limit);

    @Select("""
        select g.id, g.group_no, g.name, g.description,
               case when owner.id is null then null else g.owner_user_id end as owner_user_id,
               owner.nickname as owner_nickname, owner.avatar as owner_avatar,
               g.visibility, g.join_policy, g.category_code, g.status,
               mine.member_role as current_member_role, mine.status as current_member_status,
               (select count(*) from community_group_member members
                 where members.group_id = g.id and members.status = 'ACTIVE') as member_count,
               g.version_no, g.create_time, g.update_time
        from community_group g
        left join sys_user owner on owner.id = g.owner_user_id and owner.deleted = 0
        left join community_group_member mine
          on mine.group_id = g.id and mine.user_id = #{userId}
        where g.id = #{groupId}
        """)
    CommunityQueryRows.GroupRow findDetail(@Param("groupId") Long groupId,
                                            @Param("userId") Long userId);
}
