package com.example.venue.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.community.dto.CommunityQueryRows;
import com.example.venue.community.entity.CommunityReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CommunityReportMapper extends BaseMapper<CommunityReport> {
    @Select("select * from community_report where id = #{id} for update")
    CommunityReport selectByIdForUpdate(@Param("id") Long id);

    @Select("""
        select * from community_report
        where reporter_user_id = #{reporterUserId} and idempotency_key = #{idempotencyKey}
        limit 1
        """)
    CommunityReport findByReporterAndIdempotency(@Param("reporterUserId") Long reporterUserId,
                                                  @Param("idempotencyKey") String idempotencyKey);

    @Select("""
        select * from community_report
        where reporter_user_id = #{reporterUserId} and idempotency_key = #{idempotencyKey}
        for update
        """)
    CommunityReport findByReporterAndIdempotencyForUpdate(@Param("reporterUserId") Long reporterUserId,
                                                           @Param("idempotencyKey") String idempotencyKey);

    @Select("""
        select max(reason_retain_until) from community_report
        where target_type = #{targetType} and target_id = #{targetId}
          and status in ('RESOLVED','DISMISSED')
        """)
    LocalDateTime findMaximumReasonRetention(@Param("targetType") String targetType,
                                             @Param("targetId") Long targetId);

    @Select("""
        <script>
        select r.id, r.report_no, r.reporter_user_id,
               reporter.nickname as reporter_nickname, reporter.avatar as reporter_avatar,
               r.target_type, r.target_id, r.reason_text, r.status, r.assigned_to,
               r.decision_code, r.internal_note, r.closed_at, r.create_time, r.update_time
        from community_report r
        left join sys_user reporter on reporter.id = r.reporter_user_id and reporter.deleted = 0
        where 1 = 1
        <if test="status != null and status != ''">
          and r.status = #{status}
        </if>
        <if test="targetType != null and targetType != ''">
          and r.target_type = #{targetType}
        </if>
        <if test="cursorId != null">
          and r.id &lt; #{cursorId}
        </if>
        order by r.id desc
        limit #{limit}
        </script>
        """)
    List<CommunityQueryRows.ReportRow> listForAdmin(@Param("status") String status,
                                                    @Param("targetType") String targetType,
                                                    @Param("cursorId") Long cursorId,
                                                    @Param("limit") int limit);
}
