package com.example.venue.notice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.notice.entity.NoticeAudienceSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface NoticeAudienceSnapshotMapper extends BaseMapper<NoticeAudienceSnapshot> {
    @Select("""
        select * from notice_audience_snapshot
        where batch_id = #{batchId} and user_id = #{userId} and deleted = 0
        limit 1
        """)
    NoticeAudienceSnapshot findByBatchAndUser(@Param("batchId") Long batchId, @Param("userId") Long userId);

    @Select("""
        select * from notice_audience_snapshot
        where message_id = #{messageId} and deleted = 0
        limit 1
        """)
    NoticeAudienceSnapshot findByMessageId(@Param("messageId") Long messageId);
}
