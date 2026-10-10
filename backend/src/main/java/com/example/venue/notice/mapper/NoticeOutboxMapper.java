package com.example.venue.notice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.notice.entity.NoticeOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface NoticeOutboxMapper extends BaseMapper<NoticeOutbox> {
    @Select("select * from notice_outbox where outbox_no = #{outboxNo} and deleted = 0 limit 1")
    NoticeOutbox findByOutboxNo(@Param("outboxNo") String outboxNo);

    @Select("""
        select * from notice_outbox
        where provider_env = #{providerEnv}
          and provider_name = #{providerName}
          and idempotency_key = #{idempotencyKey}
          and deleted = 0
        limit 1
        """)
    NoticeOutbox findByIdempotency(@Param("providerEnv") String providerEnv,
                                   @Param("providerName") String providerName,
                                   @Param("idempotencyKey") String idempotencyKey);

    @Select("""
        select * from notice_outbox
        where provider_env = #{providerEnv}
          and provider_name = #{providerName}
          and idempotency_key = #{idempotencyKey}
          and deleted = 0
        limit 1
        for update
        """)
    NoticeOutbox findByIdempotencyForUpdate(@Param("providerEnv") String providerEnv,
                                            @Param("providerName") String providerName,
                                            @Param("idempotencyKey") String idempotencyKey);

    @Select("""
        select * from notice_outbox
        where event_type = #{eventType}
          and event_key = #{eventKey}
          and user_id = #{userId}
          and provider_key = #{providerKey}
          and config_version = #{configVersion}
          and deleted = 0
        limit 1
        """)
    NoticeOutbox findEmailRoute(@Param("eventType") String eventType,
                                @Param("eventKey") String eventKey,
                                @Param("userId") Long userId,
                                @Param("providerKey") String providerKey,
                                @Param("configVersion") String configVersion);

    @Select("""
        select * from notice_outbox
        where event_type = #{eventType}
          and event_key = #{eventKey}
          and user_id = #{userId}
          and provider_key = #{providerKey}
          and config_version = #{configVersion}
          and deleted = 0
        limit 1
        for update
        """)
    NoticeOutbox findEmailRouteForUpdate(@Param("eventType") String eventType,
                                         @Param("eventKey") String eventKey,
                                         @Param("userId") Long userId,
                                         @Param("providerKey") String providerKey,
                                         @Param("configVersion") String configVersion);

    @Select("""
        select * from notice_outbox
        where id = #{id} and deleted = 0
        for update
        """)
    NoticeOutbox lockForInAppDelivery(@Param("id") Long id);

    @Select("""
        select id from notice_outbox
        where deleted = 0
          and (
            status = 'PENDING'
            or (status = 'RETRY_WAITING' and next_retry_time <= #{now})
            or (status = 'SENDING' and update_time < #{staleBefore})
          )
        order by update_time asc, id asc
        limit #{limit}
        """)
    List<Long> findDueIds(@Param("now") LocalDateTime now,
                          @Param("staleBefore") LocalDateTime staleBefore,
                          @Param("limit") int limit);

    @Update("""
        update notice_outbox
           set status = 'SENDING',
               attempt_count = attempt_count + 1,
               update_time = #{now}
         where id = #{id}
           and deleted = 0
           and attempt_count = #{expectedAttemptCount}
           and (
             status = 'PENDING'
             or (status = 'RETRY_WAITING' and next_retry_time <= #{now})
             or (status = 'SENDING' and update_time < #{staleBefore})
           )
        """)
    int claimForDelivery(@Param("id") Long id,
                         @Param("expectedAttemptCount") int expectedAttemptCount,
                         @Param("now") LocalDateTime now,
                         @Param("staleBefore") LocalDateTime staleBefore);

    @Update("""
        update notice_outbox
           set status = 'SENT',
               next_retry_time = null,
               last_error_code = null,
               last_error_message = null,
               response_summary_json = #{responseSummaryJson},
               update_time = #{now}
         where id = #{id}
           and deleted = 0
           and status = 'SENDING'
           and attempt_count = #{expectedAttemptCount}
        """)
    int markSent(@Param("id") Long id,
                 @Param("expectedAttemptCount") int expectedAttemptCount,
                 @Param("externalRef") String externalRef,
                 @Param("responseSummaryJson") String responseSummaryJson,
                 @Param("now") LocalDateTime now);

    @Update("""
        update notice_outbox
           set status = 'RETRY_WAITING',
               last_error_code = #{failureCode},
               last_error_message = #{failureMessage},
               response_summary_json = #{responseSummaryJson},
               next_retry_time = #{nextRetryTime},
               update_time = #{now}
         where id = #{id}
           and deleted = 0
           and status = 'SENDING'
           and attempt_count = #{expectedAttemptCount}
        """)
    int markRetryWaiting(@Param("id") Long id,
                         @Param("expectedAttemptCount") int expectedAttemptCount,
                         @Param("failureCode") String failureCode,
                         @Param("failureMessage") String failureMessage,
                         @Param("responseSummaryJson") String responseSummaryJson,
                         @Param("nextRetryTime") LocalDateTime nextRetryTime,
                         @Param("now") LocalDateTime now);

    @Update("""
        update notice_outbox
           set status = 'FAILED',
               last_error_code = #{failureCode},
               last_error_message = #{failureMessage},
               response_summary_json = #{responseSummaryJson},
               next_retry_time = null,
               update_time = #{now}
         where id = #{id}
           and deleted = 0
           and status = 'SENDING'
           and attempt_count = #{expectedAttemptCount}
        """)
    int markFailed(@Param("id") Long id,
                   @Param("expectedAttemptCount") int expectedAttemptCount,
                   @Param("failureCode") String failureCode,
                   @Param("failureMessage") String failureMessage,
                   @Param("responseSummaryJson") String responseSummaryJson,
                   @Param("now") LocalDateTime now);
}
