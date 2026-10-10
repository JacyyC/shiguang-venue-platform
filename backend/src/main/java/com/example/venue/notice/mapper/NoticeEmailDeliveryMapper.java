package com.example.venue.notice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.notice.entity.NoticeEmailDelivery;
import com.example.venue.notice.service.EmailDeliveryEvidenceContext;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface NoticeEmailDeliveryMapper extends BaseMapper<NoticeEmailDelivery> {
    @Select("select * from notice_email_delivery where outbox_id = #{outboxId} limit 1")
    NoticeEmailDelivery findByOutboxId(@Param("outboxId") Long outboxId);

    @Select("select id as snapshot_id, outbox_id, recipient_masked as masked_recipient, template_key, template_version, message_id_header from notice_email_delivery where outbox_id = #{outboxId} limit 1")
    EmailDeliveryEvidenceContext.SafeSnapshotProjection findEvidenceByOutboxId(
        @Param("outboxId") Long outboxId);

    @Update("update notice_email_delivery set acceptance_status = 'ACCEPTED', accepted_at = #{acceptedAt}, update_time = #{acceptedAt} where outbox_id = #{outboxId} and acceptance_status in ('PENDING', 'FAILED')")
    int markAccepted(@Param("outboxId") Long outboxId,
                     @Param("acceptedAt") LocalDateTime acceptedAt);

    @Update("update notice_email_delivery set acceptance_status = 'FAILED', update_time = #{now} where outbox_id = #{outboxId} and acceptance_status in ('PENDING', 'FAILED')")
    int markFailed(@Param("outboxId") Long outboxId, @Param("now") LocalDateTime now);
}
