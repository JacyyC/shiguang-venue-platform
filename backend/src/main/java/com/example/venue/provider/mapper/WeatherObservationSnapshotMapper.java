package com.example.venue.provider.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.provider.entity.WeatherObservationSnapshot;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface WeatherObservationSnapshotMapper extends BaseMapper<WeatherObservationSnapshot> {
    @Select("""
        SELECT candidate.id
        FROM weather_observation_snapshot candidate
        WHERE candidate.create_time < #{cutoff}
          AND (
              candidate.deleted = 1
              OR candidate.id <> COALESCE((
              SELECT MAX(protected_weather.id)
              FROM weather_observation_snapshot protected_weather
              WHERE protected_weather.provider_key = candidate.provider_key
                AND protected_weather.venue_id = candidate.venue_id
                AND protected_weather.deleted = 0
              ), 0)
          )
        ORDER BY candidate.id ASC
        LIMIT #{limit}
        """)
    List<Long> selectRetentionCandidateIds(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Delete({
        "<script>",
        "DELETE FROM weather_observation_snapshot WHERE id IN",
        "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
        "</script>"
    })
    int physicalDeleteIds(@Param("ids") List<Long> ids);
}
