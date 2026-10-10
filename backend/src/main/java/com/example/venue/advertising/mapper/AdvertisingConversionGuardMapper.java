package com.example.venue.advertising.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AdvertisingConversionGuardMapper {
    /**
     * The upsert takes the unique reservation lock in one statement and keeps it
     * until the surrounding conversion transaction commits. Splitting creation
     * and locking allows concurrent first replays to form InnoDB gap-lock cycles.
     */
    @Insert("""
        INSERT INTO advertising_conversion_guard (user_id, reservation_id)
        VALUES (#{userId}, #{reservationId})
        ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
        """)
    int ensureScope(@Param("userId") Long userId,
                    @Param("reservationId") Long reservationId);
}
