package com.example.venue.advertising.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.advertising.entity.AdvertisingEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface AdvertisingEventMapper extends BaseMapper<AdvertisingEvent> {
    @Select("""
        SELECT * FROM advertising_event
        WHERE event_key = #{eventKey}
        LIMIT 1
        """)
    AdvertisingEvent findByEventKey(@Param("eventKey") String eventKey);

    @Select("""
        SELECT * FROM advertising_event
        WHERE decision_id = #{decisionId} AND event_type = #{eventType}
        LIMIT 1
        """)
    AdvertisingEvent findByDecisionAndType(@Param("decisionId") Long decisionId,
                                           @Param("eventType") String eventType);

    @Select("""
        SELECT * FROM advertising_event
        WHERE user_id = #{userId}
          AND event_type = 'CLICK'
          AND result_status = 'ACCEPTED'
          AND occurred_at >= #{windowStart}
          AND occurred_at <= #{conversionAt}
        ORDER BY occurred_at DESC, id DESC
        LIMIT 1
        """)
    AdvertisingEvent findLatestAcceptedClickForAttribution(@Param("userId") Long userId,
                                                            @Param("windowStart") LocalDateTime windowStart,
                                                            @Param("conversionAt") LocalDateTime conversionAt);

    @Select("""
        SELECT COUNT(*) FROM advertising_event
        WHERE user_id = #{userId}
          AND slot_code = #{slotCode}
          AND event_type = 'CLIENT_VIEWABLE'
          AND result_status = 'ACCEPTED'
          AND occurred_at >= #{windowStart}
        """)
    int countAcceptedViewableByUserSlot(@Param("userId") Long userId,
                                        @Param("slotCode") String slotCode,
                                        @Param("windowStart") LocalDateTime windowStart);

    @Select("""
        SELECT COUNT(*) FROM advertising_event
        WHERE user_id = #{userId}
          AND creative_version_id = #{creativeVersionId}
          AND event_type = 'CLIENT_VIEWABLE'
          AND result_status = 'ACCEPTED'
          AND occurred_at >= #{windowStart}
        """)
    int countAcceptedViewableByUserCreative(@Param("userId") Long userId,
                                            @Param("creativeVersionId") Long creativeVersionId,
                                            @Param("windowStart") LocalDateTime windowStart);
}
