package com.example.venue.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.community.dto.CommunityQueryRows;
import com.example.venue.community.entity.CommunityGroupMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CommunityGroupMemberMapper extends BaseMapper<CommunityGroupMember> {
    @Select("""
        select * from community_group_member
        where group_id = #{groupId} and user_id = #{userId}
        limit 1
        """)
    CommunityGroupMember findByGroupAndUser(@Param("groupId") Long groupId,
                                             @Param("userId") Long userId);

    @Select("""
        select * from community_group_member
        where group_id = #{groupId} and user_id = #{userId}
        for update
        """)
    CommunityGroupMember findByGroupAndUserForUpdate(@Param("groupId") Long groupId,
                                                      @Param("userId") Long userId);

    @Select("""
        <script>
        select m.id, m.group_id,
               case when u.id is null then null else m.user_id end as user_id,
               u.nickname, u.avatar,
               m.member_role, m.status, m.joined_at, m.create_time
        from community_group_member m
        left join sys_user u on u.id = m.user_id and u.deleted = 0
        where m.group_id = #{groupId}
          and not exists (
            select 1 from community_user_block b
            where b.status = 'ACTIVE'
              and ((b.blocker_user_id = #{viewerUserId} and b.blocked_user_id = m.user_id)
                or (b.blocked_user_id = #{viewerUserId} and b.blocker_user_id = m.user_id))
          )
        <if test="status != null and status != ''">
          and m.status = #{status}
        </if>
        <if test="cursorId != null">
          and m.id &lt; #{cursorId}
        </if>
        order by m.id desc
        limit #{limit}
        </script>
        """)
    List<CommunityQueryRows.MemberRow> listByGroup(@Param("viewerUserId") Long viewerUserId,
                                                   @Param("groupId") Long groupId,
                                                   @Param("status") String status,
                                                   @Param("cursorId") Long cursorId,
                                                   @Param("limit") int limit);
}
