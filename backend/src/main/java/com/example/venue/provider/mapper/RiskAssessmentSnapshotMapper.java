package com.example.venue.provider.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.provider.entity.RiskAssessmentSnapshot;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RiskAssessmentSnapshotMapper extends BaseMapper<RiskAssessmentSnapshot> {
    @Select("""
        SELECT candidate.id
        FROM risk_assessment_snapshot candidate
        WHERE candidate.create_time < #{cutoff}
          AND (
              candidate.deleted = 1
              OR candidate.id <> COALESCE((
              SELECT MAX(protected_risk.id)
              FROM risk_assessment_snapshot protected_risk
              WHERE protected_risk.provider_key = candidate.provider_key
                AND protected_risk.scope_type = candidate.scope_type
                AND COALESCE(protected_risk.scope_id, 0) = COALESCE(candidate.scope_id, 0)
                AND protected_risk.deleted = 0
              ), 0)
          )
        ORDER BY candidate.id ASC
        LIMIT #{limit}
        """)
    List<Long> selectRetentionCandidateIds(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Delete({
        "<script>",
        "DELETE FROM risk_assessment_snapshot WHERE id IN",
        "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
        "</script>"
    })
    int physicalDeleteIds(@Param("ids") List<Long> ids);
}
