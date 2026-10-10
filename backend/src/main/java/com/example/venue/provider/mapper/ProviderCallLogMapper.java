package com.example.venue.provider.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.provider.entity.ProviderCallLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ProviderCallLogMapper extends BaseMapper<ProviderCallLog> {
    @Select("""
        SELECT candidate.id
        FROM provider_call_log candidate
        WHERE candidate.occurred_at < #{cutoff}
          AND candidate.id <> COALESCE(
              (
                  SELECT MAX(protected_success.id)
                  FROM provider_call_log protected_success
                  WHERE protected_success.provider_key = candidate.provider_key
                    AND protected_success.status IN ('SUCCESS', 'DEGRADED')
              ),
              (
                  SELECT MAX(protected_any.id)
                  FROM provider_call_log protected_any
                  WHERE protected_any.provider_key = candidate.provider_key
              )
          )
          AND NOT EXISTS (
              SELECT 1
              FROM weather_observation_snapshot protected_weather
              WHERE candidate.snapshot_type = 'WEATHER'
                AND candidate.snapshot_id = protected_weather.id
                AND protected_weather.deleted = 0
                AND protected_weather.id = (
                    SELECT MAX(latest_weather.id)
                    FROM weather_observation_snapshot latest_weather
                    WHERE latest_weather.provider_key = protected_weather.provider_key
                      AND latest_weather.venue_id = protected_weather.venue_id
                      AND latest_weather.deleted = 0
                )
          )
          AND NOT EXISTS (
              SELECT 1
              FROM risk_assessment_snapshot protected_risk
              WHERE candidate.snapshot_type = 'RISK'
                AND candidate.snapshot_id = protected_risk.id
                AND protected_risk.deleted = 0
                AND protected_risk.id = (
                    SELECT MAX(latest_risk.id)
                    FROM risk_assessment_snapshot latest_risk
                    WHERE latest_risk.provider_key = protected_risk.provider_key
                      AND latest_risk.scope_type = protected_risk.scope_type
                      AND COALESCE(latest_risk.scope_id, 0) = COALESCE(protected_risk.scope_id, 0)
                      AND latest_risk.deleted = 0
                )
          )
          AND NOT EXISTS (
              SELECT 1
              FROM ops_metric_snapshot protected_metric
              WHERE candidate.snapshot_type = 'METRIC'
                AND candidate.snapshot_id = protected_metric.id
                AND protected_metric.deleted = 0
                AND protected_metric.id = COALESCE(
                    (
                        SELECT MAX(latest_valid.id)
                        FROM ops_metric_snapshot latest_valid
                        WHERE latest_valid.provider_key = protected_metric.provider_key
                          AND latest_valid.metric_key = protected_metric.metric_key
                          AND latest_valid.status IN ('OK', 'DEGRADED')
                          AND latest_valid.deleted = 0
                    ),
                    (
                        SELECT MAX(latest_any.id)
                        FROM ops_metric_snapshot latest_any
                        WHERE latest_any.provider_key = protected_metric.provider_key
                          AND latest_any.metric_key = protected_metric.metric_key
                          AND latest_any.deleted = 0
                    )
                )
          )
        ORDER BY candidate.id ASC
        LIMIT #{limit}
        """)
    List<Long> selectRetentionCandidateIds(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Delete({
        "<script>",
        "DELETE FROM provider_call_log WHERE id IN",
        "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
        "</script>"
    })
    int physicalDeleteIds(@Param("ids") List<Long> ids);
}
