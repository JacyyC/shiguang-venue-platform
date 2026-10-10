package com.example.venue.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.community.dto.CommunityQueryRows;
import com.example.venue.community.entity.CommunityGovernanceEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CommunityGovernanceEventMapper extends BaseMapper<CommunityGovernanceEvent> {
    @Select("""
        select * from community_governance_event
        where source_type = #{sourceType}
          and event_type = #{eventType}
          and subject_type = #{subjectType}
          and subject_id = #{subjectId}
          and idempotency_key = #{idempotencyKey}
        limit 1
        for update
        """)
    CommunityGovernanceEvent findRecorded(@Param("sourceType") String sourceType,
                                           @Param("eventType") String eventType,
                                           @Param("subjectType") String subjectType,
                                           @Param("subjectId") Long subjectId,
                                           @Param("idempotencyKey") String idempotencyKey);

    @Select("""
        <script>
        select id, event_no, event_type, subject_type, subject_id, report_id,
               actor_user_id, actor_scope, source_type, from_status, to_status,
               reason_code, reason_summary, result_status, occurred_at
        from community_governance_event
        where 1 = 1
        <if test="subjectType != null and subjectType != ''">
          and subject_type = #{subjectType}
        </if>
        <if test="subjectId != null">
          and subject_id = #{subjectId}
        </if>
        <if test="reportId != null">
          and report_id = #{reportId}
        </if>
        <if test="cursorId != null">
          and id &lt; #{cursorId}
        </if>
        order by id desc
        limit #{limit}
        </script>
        """)
    List<CommunityQueryRows.EventRow> listForAdmin(@Param("subjectType") String subjectType,
                                                   @Param("subjectId") Long subjectId,
                                                   @Param("reportId") Long reportId,
                                                   @Param("cursorId") Long cursorId,
                                                   @Param("limit") int limit);
}
