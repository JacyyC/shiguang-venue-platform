package com.example.venue.advertising.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.advertising.entity.AdvertisingConversionCallback;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AdvertisingConversionCallbackMapper extends BaseMapper<AdvertisingConversionCallback> {
    @Select("""
        SELECT * FROM advertising_conversion_callback
        WHERE event_key = #{eventKey}
        LIMIT 1 FOR UPDATE
        """)
    AdvertisingConversionCallback findByEventKeyForUpdate(@Param("eventKey") String eventKey);

    @Select("""
        SELECT * FROM advertising_conversion_callback
        WHERE status IN ('PENDING', 'PENDING_RETRY')
          AND next_retry_at IS NOT NULL
          AND next_retry_at <= #{now}
        ORDER BY id
        LIMIT #{limit}
        """)
    List<AdvertisingConversionCallback> findPendingRetry(@Param("now") LocalDateTime now,
                                                         @Param("limit") int limit);

    @Select("""
        SELECT COUNT(*) FROM advertising_conversion_callback
        WHERE result_code = #{resultCode}
          AND occurred_at >= #{from}
          AND occurred_at < #{until}
        """)
    long countByResultCode(@Param("resultCode") String resultCode,
                           @Param("from") LocalDateTime from,
                           @Param("until") LocalDateTime until);

    @Select("""
        SELECT COUNT(*) FROM advertising_conversion_callback
        WHERE status = 'PENDING_RETRY'
          AND occurred_at >= #{from}
          AND occurred_at < #{until}
        """)
    long countPendingRetry(@Param("from") LocalDateTime from,
                           @Param("until") LocalDateTime until);
}
