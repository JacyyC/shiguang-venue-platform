package com.example.venue.community.activity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.community.activity.entity.CommunityActivityCommand;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface CommunityActivityCommandMapper extends BaseMapper<CommunityActivityCommand> {
    @Select("""
        select * from community_activity_command
        where actor_user_id = #{actorUserId} and command_type = #{commandType}
          and idempotency_key = #{idempotencyKey}
        limit 1 for update
        """)
    CommunityActivityCommand findForUpdate(@Param("actorUserId") Long actorUserId,
                                           @Param("commandType") String commandType,
                                           @Param("idempotencyKey") String idempotencyKey);

    @Update("""
        update community_activity_command
        set target_type = #{targetType}, target_id = #{targetId}, result_status = #{resultStatus},
            status = 'COMPLETED', completed_at = #{completedAt}
        where id = #{id} and status = 'PROCESSING'
        """)
    int complete(@Param("id") Long id,
                 @Param("targetType") String targetType,
                 @Param("targetId") Long targetId,
                 @Param("resultStatus") String resultStatus,
                 @Param("completedAt") LocalDateTime completedAt);
}
