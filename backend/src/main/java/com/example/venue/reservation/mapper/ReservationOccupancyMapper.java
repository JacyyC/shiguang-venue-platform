package com.example.venue.reservation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.reservation.entity.ReservationOccupancy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Mapper
public interface ReservationOccupancyMapper extends BaseMapper<ReservationOccupancy> {
    @Update("""
        update reservation_occupancy
        set active_flag = null,
            released_at = coalesce(released_at, current_timestamp),
            release_reason = coalesce(release_reason, 'LEGACY_RELEASE')
        where reservation_id = #{reservationId} and active_flag = 1
        """)
    int releaseByReservationId(@Param("reservationId") Long reservationId);

    @Update("""
        update reservation_occupancy
        set active_flag = null,
            released_at = coalesce(released_at, current_timestamp),
            release_reason = coalesce(#{reason}, release_reason, 'RESOURCE_RELEASED')
        where reservation_id = #{reservationId} and active_flag = 1
        """)
    int releaseByReservationIdWithReason(@Param("reservationId") Long reservationId,
                                         @Param("reason") String reason);

    // Bound locking reads to this resource's index range. ORDER BY id can otherwise
    // make MySQL scan PRIMARY and lock unrelated resources, creating cross-space deadlocks.
    @Select("""
        <script>
        select *
        from reservation_occupancy force index (idx_reservation_occupancy_space_time_active)
        where space_id = #{spaceId}
          and slot_date = #{slotDate}
          and active_flag = 1
          and start_time &lt; #{endTime}
          and end_time &gt; #{startTime}
          <if test="excludedReservationId != null">
            and reservation_id != #{excludedReservationId}
          </if>
        order by id
        for update
        </script>
        """)
    List<ReservationOccupancy> findActiveOverlapsForUpdate(@Param("spaceId") Long spaceId,
                                                           @Param("slotDate") LocalDate slotDate,
                                                           @Param("startTime") LocalTime startTime,
                                                           @Param("endTime") LocalTime endTime,
                                                           @Param("excludedReservationId") Long excludedReservationId);

    @Select("""
        <script>
        select *
        from reservation_occupancy
        where space_id = #{spaceId}
          and slot_date = #{slotDate}
          and active_flag = 1
          and start_time &lt; #{endTime}
          and end_time &gt; #{startTime}
          <if test="excludedReservationId != null">
            and reservation_id != #{excludedReservationId}
          </if>
        order by id
        </script>
        """)
    List<ReservationOccupancy> findActiveOverlaps(@Param("spaceId") Long spaceId,
                                                  @Param("slotDate") LocalDate slotDate,
                                                  @Param("startTime") LocalTime startTime,
                                                  @Param("endTime") LocalTime endTime,
                                                  @Param("excludedReservationId") Long excludedReservationId);

    @Select("""
        select * from reservation_occupancy
        where reservation_id = #{reservationId} and active_flag = 1
        limit 1 for update
        """)
    ReservationOccupancy selectActiveByReservationIdForUpdate(@Param("reservationId") Long reservationId);

    @Select("""
        select * from reservation_occupancy
        where reservation_id = #{reservationId} and active_flag = 1
        limit 1
        """)
    ReservationOccupancy selectActiveByReservationId(@Param("reservationId") Long reservationId);

    @Select("""
        select count(*)
        from reservation_occupancy
        where space_id = #{spaceId}
          and active_flag = 1
          and timestamp(slot_date, end_time) > #{now}
        """)
    long countFutureActiveBySpace(@Param("spaceId") Long spaceId, @Param("now") LocalDateTime now);
}
