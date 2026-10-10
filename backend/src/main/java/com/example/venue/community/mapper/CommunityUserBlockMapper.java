package com.example.venue.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.community.dto.CommunityQueryRows;
import com.example.venue.community.entity.CommunityUserBlock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CommunityUserBlockMapper extends BaseMapper<CommunityUserBlock> {
    @Select("""
        select * from community_user_block
        where blocker_user_id = #{blockerUserId} and blocked_user_id = #{blockedUserId}
        limit 1
        """)
    CommunityUserBlock findPair(@Param("blockerUserId") Long blockerUserId,
                                @Param("blockedUserId") Long blockedUserId);

    @Select("""
        select * from community_user_block
        where blocker_user_id = #{blockerUserId} and blocked_user_id = #{blockedUserId}
        for update
        """)
    CommunityUserBlock findPairForUpdate(@Param("blockerUserId") Long blockerUserId,
                                         @Param("blockedUserId") Long blockedUserId);

    @Select("""
        select count(*) from community_user_block
        where status = 'ACTIVE'
          and ((blocker_user_id = #{firstUserId} and blocked_user_id = #{secondUserId})
            or (blocker_user_id = #{secondUserId} and blocked_user_id = #{firstUserId}))
        """)
    long countActiveEitherDirection(@Param("firstUserId") Long firstUserId,
                                    @Param("secondUserId") Long secondUserId);

    @Select("""
        <script>
        select b.id,
               case when u.id is null then null else b.blocked_user_id end as blocked_user_id,
               u.nickname, u.avatar, b.created_at
        from community_user_block b
        left join sys_user u on u.id = b.blocked_user_id and u.deleted = 0
        where b.blocker_user_id = #{userId} and b.status = 'ACTIVE'
        <if test="cursorId != null">
          and b.id &lt; #{cursorId}
        </if>
        order by b.id desc
        limit #{limit}
        </script>
        """)
    List<CommunityQueryRows.BlockRow> listActive(@Param("userId") Long userId,
                                                  @Param("cursorId") Long cursorId,
                                                  @Param("limit") int limit);
}
