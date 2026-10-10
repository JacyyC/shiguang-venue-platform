package com.example.venue.community.activity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.community.activity.entity.CommunityActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CommunityActivityMapper extends BaseMapper<CommunityActivity> {
    @Select("select * from community_activity where id = #{id} for update")
    CommunityActivity selectByIdForUpdate(@Param("id") Long id);

    @Select("""
        <script>
        select a.* from community_activity a
        join community_group g on g.id = a.group_id and g.status = 'ACTIVE'
        left join community_post p on p.id = a.reference_post_id
        where a.status in ('PUBLISHED','FULL')
          and a.end_time &gt; #{now}
          and (a.reference_post_id is null
            or (p.status = 'PUBLISHED' and p.group_id = a.group_id))
          and (g.visibility = 'PLATFORM_VISIBLE' or exists (
            select 1 from community_group_member gm
            where gm.group_id = a.group_id and gm.user_id = #{viewerUserId} and gm.status = 'ACTIVE'
          ))
          and not exists (
            select 1 from community_user_block b
            where b.status = 'ACTIVE' and (
              (b.blocker_user_id = #{viewerUserId}
                and b.blocked_user_id in (a.organizer_user_id, g.owner_user_id, p.author_user_id))
              or (b.blocked_user_id = #{viewerUserId}
                and b.blocker_user_id in (a.organizer_user_id, g.owner_user_id, p.author_user_id))
            )
          )
        <if test="activityType != null">
          and a.activity_type = #{activityType}
        </if>
        <if test="keyword != null">
          and (a.title like concat('%', #{keyword}, '%')
            or a.description like concat('%', #{keyword}, '%'))
        </if>
        <if test="fromTime != null">
          and a.end_time &gt; #{fromTime}
        </if>
        <if test="toTime != null">
          and a.start_time &lt; #{toTime}
        </if>
        <if test="venueId != null">
          and a.venue_id = #{venueId}
        </if>
        <if test="areaCode != null">
          and a.area_code = #{areaCode}
        </if>
        <if test="cursorId != null">
          and a.id &lt; #{cursorId}
        </if>
        order by a.id desc
        limit #{limit}
        </script>
        """)
    List<CommunityActivity> discover(@Param("viewerUserId") Long viewerUserId,
                                     @Param("keyword") String keyword,
                                     @Param("activityType") String activityType,
                                     @Param("fromTime") LocalDateTime fromTime,
                                     @Param("toTime") LocalDateTime toTime,
                                     @Param("venueId") Long venueId,
                                     @Param("areaCode") String areaCode,
                                     @Param("cursorId") Long cursorId,
                                     @Param("now") LocalDateTime now,
                                     @Param("limit") int limit);

    @Select("""
        select * from community_activity
        where reservation_id = #{reservationId}
          and location_commitment_status = 'CONFIRMED'
          and id > #{afterId}
        order by id
        limit #{limit}
        for update
        """)
    List<CommunityActivity> findConfirmedByReservationForUpdate(
        @Param("reservationId") Long reservationId,
        @Param("afterId") Long afterId,
        @Param("limit") int limit);

    @Select("""
        select id from community_activity
        where location_commitment_status = 'CONFIRMED' and id > #{afterId}
        order by id
        limit #{limit}
        """)
    List<Long> findConfirmedIds(@Param("afterId") Long afterId, @Param("limit") int limit);

    @Select("""
        select id from community_activity
        where status in ('PUBLISHED','FULL') and end_time <= #{now}
        order by end_time, id
        limit #{limit}
        """)
    List<Long> findDueExpiryIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
        update community_activity
        set location_commitment_status = 'DEGRADED', commitment_version = commitment_version + 1,
            version_no = version_no + 1, update_time = #{now}
        where id = #{id} and location_commitment_status = 'CONFIRMED'
          and commitment_version = #{expectedCommitmentVersion}
        """)
    int degradeCommitment(@Param("id") Long id,
                          @Param("expectedCommitmentVersion") Long expectedCommitmentVersion,
                          @Param("now") LocalDateTime now);

    @Update("""
        update community_activity
        set location_type = #{locationType}, venue_id = #{venueId}, space_id = #{spaceId},
            area_code = #{areaCode}, reservation_id = #{reservationId},
            location_commitment_status = #{commitmentStatus}, commitment_version = #{commitmentVersion},
            version_no = #{nextVersion}, update_time = #{now}
        where id = #{id} and version_no = #{expectedVersion}
        """)
    int updateLocation(@Param("id") Long id,
                       @Param("expectedVersion") Long expectedVersion,
                       @Param("nextVersion") Long nextVersion,
                       @Param("locationType") String locationType,
                       @Param("venueId") Long venueId,
                       @Param("spaceId") Long spaceId,
                       @Param("areaCode") String areaCode,
                       @Param("reservationId") Long reservationId,
                       @Param("commitmentStatus") String commitmentStatus,
                       @Param("commitmentVersion") Long commitmentVersion,
                       @Param("now") LocalDateTime now);
}
