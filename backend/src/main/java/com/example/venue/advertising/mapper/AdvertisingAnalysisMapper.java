package com.example.venue.advertising.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface AdvertisingAnalysisMapper {
    @Select("""
        SELECT COUNT(*) FROM advertising_decision
        WHERE create_time >= #{from} AND create_time < #{until}
        """)
    long countDecisions(@Param("from") LocalDateTime from,
                        @Param("until") LocalDateTime until);

    @Select("""
        SELECT COUNT(*) FROM advertising_event
        WHERE event_type = 'CLIENT_VIEWABLE'
          AND result_status = 'ACCEPTED'
          AND occurred_at >= #{from} AND occurred_at < #{until}
        """)
    long countViewableEvents(@Param("from") LocalDateTime from,
                             @Param("until") LocalDateTime until);

    @Select("""
        SELECT COUNT(*) FROM advertising_event
        WHERE event_type = 'CLICK'
          AND result_status = 'ACCEPTED'
          AND occurred_at >= #{from} AND occurred_at < #{until}
        """)
    long countClickEvents(@Param("from") LocalDateTime from,
                          @Param("until") LocalDateTime until);
}
