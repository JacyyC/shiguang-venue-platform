package com.example.venue.community.activity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.community.activity.entity.CommunityActivityParticipation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CommunityActivityParticipationMapper extends BaseMapper<CommunityActivityParticipation> {
    @Select("select * from community_activity_participation where id = #{id} for update")
    CommunityActivityParticipation selectByIdForUpdate(@Param("id") Long id);

    @Select("""
        select * from community_activity_participation
        where target_type = #{targetType} and target_id = #{targetId} and user_id = #{userId}
        limit 1 for update
        """)
    CommunityActivityParticipation findForUpdate(@Param("targetType") String targetType,
                                                 @Param("targetId") Long targetId,
                                                 @Param("userId") Long userId);

    @Select("""
        select * from community_activity_participation
        where target_type = #{targetType} and target_id = #{targetId} and user_id = #{userId}
        limit 1
        """)
    CommunityActivityParticipation find(@Param("targetType") String targetType,
                                        @Param("targetId") Long targetId,
                                        @Param("userId") Long userId);

    @Select("""
        <script>
        select p.* from community_activity_participation p
        where p.target_type = #{targetType} and p.target_id = #{targetId} and p.status = 'ACTIVE'
          and (
            p.user_id = #{viewerUserId}
            or (
              not exists (
                select 1 from community_user_block outbound
                where outbound.status = 'ACTIVE'
                  and outbound.blocker_user_id = #{viewerUserId}
                  and outbound.blocked_user_id = p.user_id
              )
              and not exists (
                select 1 from community_user_block inbound
                where inbound.status = 'ACTIVE'
                  and inbound.blocker_user_id = p.user_id
                  and inbound.blocked_user_id = #{viewerUserId}
              )
            )
          )
        <if test="cursorId != null">
          and p.id &lt; #{cursorId}
        </if>
        order by p.id desc
        limit #{limit}
        </script>
        """)
    List<CommunityActivityParticipation> listActiveVisibleTo(@Param("targetType") String targetType,
                                                             @Param("targetId") Long targetId,
                                                             @Param("viewerUserId") Long viewerUserId,
                                                             @Param("cursorId") Long cursorId,
                                                             @Param("limit") int limit);

    @Select("""
        <script>
        select * from community_activity_participation
        where target_type = #{targetType} and target_id = #{targetId} and status = 'ACTIVE'
        <if test="cursorId != null">
          and id &lt; #{cursorId}
        </if>
        order by id desc
        limit #{limit}
        </script>
        """)
    List<CommunityActivityParticipation> listActive(@Param("targetType") String targetType,
                                                    @Param("targetId") Long targetId,
                                                    @Param("cursorId") Long cursorId,
                                                    @Param("limit") int limit);

    @Update("""
        update community_activity_participation
        set status = 'CANCELED', left_at = #{now}, update_time = #{now}, version_no = version_no + 1
        where target_type = #{targetType} and target_id = #{targetId} and status = 'ACTIVE'
        """)
    int cancelAll(@Param("targetType") String targetType,
                  @Param("targetId") Long targetId,
                  @Param("now") LocalDateTime now);

    @Update("""
        update community_activity_participation
        set status = 'ACTIVE', application_id = #{applicationId}, joined_at = #{joinedAt},
            left_at = null, version_no = #{nextVersion}, update_time = #{now}
        where id = #{id} and version_no = #{expectedVersion}
        """)
    int reactivate(@Param("id") Long id,
                   @Param("expectedVersion") Long expectedVersion,
                   @Param("nextVersion") Long nextVersion,
                   @Param("applicationId") Long applicationId,
                   @Param("joinedAt") LocalDateTime joinedAt,
                   @Param("now") LocalDateTime now);
}
