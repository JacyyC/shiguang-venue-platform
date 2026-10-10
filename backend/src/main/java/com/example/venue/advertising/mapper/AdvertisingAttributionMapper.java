package com.example.venue.advertising.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.advertising.entity.AdvertisingAttribution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface AdvertisingAttributionMapper extends BaseMapper<AdvertisingAttribution> {
    @Select("""
        SELECT * FROM advertising_attribution
        WHERE reservation_id = #{reservationId}
        LIMIT 1 FOR UPDATE
        """)
    AdvertisingAttribution findByReservationIdForUpdate(@Param("reservationId") Long reservationId);

    @Select("""
        SELECT COUNT(*) FROM advertising_attribution
        WHERE status = #{status}
          AND conversion_occurred_at >= #{from}
          AND conversion_occurred_at < #{until}
        """)
    long countByStatus(@Param("status") String status,
                       @Param("from") LocalDateTime from,
                       @Param("until") LocalDateTime until);

    @Select("""
        SELECT COUNT(*)
        FROM advertising_event event
        LEFT JOIN advertising_attribution attribution
          ON attribution.click_event_id = event.id
        WHERE event.event_type = 'CLICK'
          AND event.result_status = 'ACCEPTED'
          AND event.occurred_at >= #{from}
          AND event.occurred_at < #{until}
          AND attribution.id IS NULL
        """)
    long countUnmatchedAcceptedClicks(@Param("from") LocalDateTime from,
                                      @Param("until") LocalDateTime until);
}
