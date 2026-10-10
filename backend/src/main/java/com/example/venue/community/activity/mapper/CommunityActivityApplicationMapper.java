package com.example.venue.community.activity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.community.activity.entity.CommunityActivityApplication;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CommunityActivityApplicationMapper extends BaseMapper<CommunityActivityApplication> {
    @Select("select * from community_activity_application where id = #{id} for update")
    CommunityActivityApplication selectByIdForUpdate(@Param("id") Long id);

    @Select("""
        select * from community_activity_application
        where target_type = #{targetType} and target_id = #{targetId}
          and applicant_user_id = #{userId}
        limit 1 for update
        """)
    CommunityActivityApplication findForUpdate(@Param("targetType") String targetType,
                                               @Param("targetId") Long targetId,
                                               @Param("userId") Long userId);

    @Select("""
        select * from community_activity_application
        where target_type = #{targetType} and target_id = #{targetId}
          and applicant_user_id = #{userId}
        limit 1
        """)
    CommunityActivityApplication find(@Param("targetType") String targetType,
                                      @Param("targetId") Long targetId,
                                      @Param("userId") Long userId);

    @Select("""
        <script>
        select application.* from community_activity_application application
        join community_group_member member
          on member.group_id = #{groupId}
          and member.user_id = application.applicant_user_id
          and member.status = 'ACTIVE'
        where application.target_type = #{targetType} and application.target_id = #{targetId}
          and not exists (
            select 1 from community_user_block block
            where block.status = 'ACTIVE' and (
              (block.blocker_user_id = #{ownerUserId}
                and block.blocked_user_id = application.applicant_user_id)
              or (block.blocked_user_id = #{ownerUserId}
                and block.blocker_user_id = application.applicant_user_id)
            )
          )
        <if test="status != null">
          and application.status = #{status}
        </if>
        <if test="cursorId != null">
          and application.id &lt; #{cursorId}
        </if>
        order by application.id desc
        limit #{limit}
        </script>
        """)
    List<CommunityActivityApplication> listByTarget(@Param("targetType") String targetType,
                                                    @Param("targetId") Long targetId,
                                                    @Param("groupId") Long groupId,
                                                    @Param("ownerUserId") Long ownerUserId,
                                                    @Param("status") String status,
                                                    @Param("cursorId") Long cursorId,
                                                    @Param("limit") int limit);

    @Update("""
        update community_activity_application
        set status = 'PENDING', reason_code = null, applied_at = #{appliedAt},
            decided_by = null, decided_at = null, withdrawn_at = null,
            version_no = #{nextVersion}, update_time = #{now}
        where id = #{id} and version_no = #{expectedVersion}
        """)
    int resetToPending(@Param("id") Long id,
                       @Param("expectedVersion") Long expectedVersion,
                       @Param("nextVersion") Long nextVersion,
                       @Param("appliedAt") LocalDateTime appliedAt,
                       @Param("now") LocalDateTime now);
}
