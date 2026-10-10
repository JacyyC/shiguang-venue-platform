package com.example.venue.placement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.placement.entity.PlacementEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PlacementEventMapper extends BaseMapper<PlacementEvent> {

    /**
     * 频控权威计数：在 (user, slot, candidate, window) 内已接受的 CLIENT_VIEWABLE 数量。
     * 调用方在决策 guard 锁内调用，确保与决策签发串行化。
     */
    @Select("""
        SELECT COUNT(*) FROM placement_event
        WHERE user_id = #{userId}
          AND slot_id = #{slotId}
          AND candidate_version_id = #{candidateVersionId}
          AND event_type = 'CLIENT_VIEWABLE'
          AND result_status = 'ACCEPTED'
          AND occurred_at >= #{windowStart}
        """)
    int countAcceptedViewable(@Param("userId") Long userId,
                              @Param("slotId") Long slotId,
                              @Param("candidateVersionId") Long candidateVersionId,
                              @Param("windowStart") LocalDateTime windowStart);

    /**
     * 读取已存在的幂等事件。新键不做 FOR UPDATE，避免空范围间隙锁与并发 INSERT 形成死锁；
     * 新事件由普通 INSERT 的唯一键异常仲裁。
     */
    @Select("""
        SELECT * FROM placement_event
        WHERE event_key = #{eventKey}
        """)
    PlacementEvent findByKey(@Param("eventKey") String eventKey);

    /**
     * 通过 event_key 与 (decision_id,event_type) 唯一键完成并发幂等仲裁。
     * 调用方只捕获已知 DuplicateKeyException 并读取原事件；其它数据库错误必须可见。
     */
    @Insert("""
        INSERT INTO placement_event (
          event_no, event_key, event_type, decision_id, user_id, slot_id,
          candidate_version_id, request_hash, visible_ratio, visible_duration_ms,
          result_status, rejection_code, occurred_at, create_time
        ) VALUES (
          #{event.eventNo}, #{event.eventKey}, #{event.eventType}, #{event.decisionId},
          #{event.userId}, #{event.slotId}, #{event.candidateVersionId}, #{event.requestHash},
          #{event.visibleRatio}, #{event.visibleDurationMs}, #{event.resultStatus},
          #{event.rejectionCode}, #{event.occurredAt}, #{event.createTime}
        )
        """)
    int insertEvent(@Param("event") PlacementEvent event);

    @Select("""
        SELECT * FROM placement_event
        WHERE decision_id = #{decisionId}
          AND event_type = #{eventType}
        LIMIT 1
        """)
    PlacementEvent findByDecisionAndType(@Param("decisionId") Long decisionId,
                                         @Param("eventType") String eventType);

    @Select("""
        SELECT * FROM placement_event
        FORCE INDEX (idx_placement_event_slot_time)
        WHERE slot_id = #{slotId}
          AND occurred_at >= #{from}
          AND occurred_at < #{to}
        ORDER BY occurred_at DESC, id DESC
        LIMIT #{limit}
        """)
    List<PlacementEvent> findRecentBySlot(@Param("slotId") Long slotId,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to,
                                          @Param("limit") int limit);

    @Select("""
        SELECT * FROM placement_event
        WHERE decision_id = #{decisionId}
        ORDER BY occurred_at DESC, id DESC
        LIMIT #{limit}
        """)
    List<PlacementEvent> findByDecision(@Param("decisionId") Long decisionId,
                                        @Param("limit") int limit);
}
