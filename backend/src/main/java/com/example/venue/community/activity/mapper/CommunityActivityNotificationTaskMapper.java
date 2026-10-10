package com.example.venue.community.activity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.community.activity.entity.CommunityActivityNotificationTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CommunityActivityNotificationTaskMapper extends BaseMapper<CommunityActivityNotificationTask> {
    @Select("""
        select id from community_activity_notification_task
        where status = 'PENDING'
           or (status = 'RETRY_WAITING' and next_retry_time <= #{now})
           or (status = 'DISPATCHING' and claimed_at < #{staleBefore})
        order by update_time, id
        limit #{limit}
        """)
    List<Long> findDueIds(@Param("now") LocalDateTime now,
                          @Param("staleBefore") LocalDateTime staleBefore,
                          @Param("limit") int limit);

    @Update("""
        update community_activity_notification_task
        set status = 'DISPATCHING', attempt_count = attempt_count + 1,
            next_retry_time = null, claimed_at = #{now}, update_time = #{now}
        where id = #{id} and attempt_count = #{expectedAttemptCount}
          and (status = 'PENDING'
            or (status = 'RETRY_WAITING' and next_retry_time <= #{now})
            or (status = 'DISPATCHING' and claimed_at < #{staleBefore}))
        """)
    int claim(@Param("id") Long id,
              @Param("expectedAttemptCount") int expectedAttemptCount,
              @Param("now") LocalDateTime now,
              @Param("staleBefore") LocalDateTime staleBefore);

    @Update("""
        update community_activity_notification_task
        set status = 'DELIVERED', delivered_at = #{now}, next_retry_time = null, claimed_at = null,
            last_error_code = null, last_error_message = null, update_time = #{now}
        where id = #{id} and status = 'DISPATCHING' and attempt_count = #{attemptCount}
        """)
    int markDelivered(@Param("id") Long id,
                      @Param("attemptCount") int attemptCount,
                      @Param("now") LocalDateTime now);

    @Update("""
        update community_activity_notification_task
        set status = 'RETRY_WAITING', next_retry_time = #{nextRetryTime}, claimed_at = null,
            last_error_code = #{errorCode}, last_error_message = #{errorMessage}, update_time = #{now}
        where id = #{id} and status = 'DISPATCHING' and attempt_count = #{attemptCount}
        """)
    int markRetry(@Param("id") Long id,
                  @Param("attemptCount") int attemptCount,
                  @Param("errorCode") String errorCode,
                  @Param("errorMessage") String errorMessage,
                  @Param("nextRetryTime") LocalDateTime nextRetryTime,
                  @Param("now") LocalDateTime now);

    @Update("""
        update community_activity_notification_task
        set status = 'FAILED', next_retry_time = null, claimed_at = null,
            last_error_code = #{errorCode}, last_error_message = #{errorMessage}, update_time = #{now}
        where id = #{id} and status = 'DISPATCHING' and attempt_count = #{attemptCount}
        """)
    int markFailed(@Param("id") Long id,
                   @Param("attemptCount") int attemptCount,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage,
                   @Param("now") LocalDateTime now);

    @Update("""
        update community_activity_notification_task
        set status = 'FAILED', next_retry_time = null, claimed_at = null,
            last_error_code = #{errorCode}, last_error_message = #{errorMessage}, update_time = #{now}
        where id = #{id} and status = 'DISPATCHING' and attempt_count = #{attemptCount}
          and claimed_at < #{staleBefore}
        """)
    int markStaleExhausted(@Param("id") Long id,
                           @Param("attemptCount") int attemptCount,
                           @Param("errorCode") String errorCode,
                           @Param("errorMessage") String errorMessage,
                           @Param("now") LocalDateTime now,
                           @Param("staleBefore") LocalDateTime staleBefore);
}
