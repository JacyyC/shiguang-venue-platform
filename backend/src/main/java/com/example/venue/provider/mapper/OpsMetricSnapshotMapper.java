package com.example.venue.provider.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.provider.entity.OpsMetricSnapshot;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OpsMetricSnapshotMapper extends BaseMapper<OpsMetricSnapshot> {
    @Select("""
        SELECT candidate.id
        FROM ops_metric_snapshot candidate
        WHERE candidate.create_time < #{cutoff}
          AND (
              candidate.deleted = 1
              OR candidate.id <> COALESCE(
              (
                  SELECT MAX(protected_valid.id)
                  FROM ops_metric_snapshot protected_valid
                  WHERE protected_valid.provider_key = candidate.provider_key
                    AND protected_valid.metric_key = candidate.metric_key
                    AND protected_valid.status IN ('OK', 'DEGRADED')
                    AND protected_valid.deleted = 0
              ),
              (
                  SELECT MAX(protected_any.id)
                  FROM ops_metric_snapshot protected_any
                  WHERE protected_any.provider_key = candidate.provider_key
                    AND protected_any.metric_key = candidate.metric_key
                    AND protected_any.deleted = 0
              ),
              0
              )
          )
        ORDER BY candidate.id ASC
        LIMIT #{limit}
        """)
    List<Long> selectRetentionCandidateIds(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Delete({
        "<script>",
        "DELETE FROM ops_metric_snapshot WHERE id IN",
        "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
        "</script>"
    })
    int physicalDeleteIds(@Param("ids") List<Long> ids);
}
