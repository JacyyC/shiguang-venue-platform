package com.example.venue.resource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.resource.entity.ReservationOccupancySeat;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;

@Mapper
public interface ReservationOccupancySeatMapper extends BaseMapper<ReservationOccupancySeat> {
    @Select("""
        select seat_allocation.seat_id
        from reservation_occupancy_seat seat_allocation
        join reservation_occupancy occupancy on occupancy.id = seat_allocation.occupancy_id
        where occupancy.reservation_id = #{reservationId}
          and occupancy.active_flag = 1
        order by seat_allocation.id
        """)
    List<Long> findActiveSeatIdsByReservationId(@Param("reservationId") Long reservationId);

    @Select("""
        <script>
        select seat_id
        from reservation_occupancy_seat
        where occupancy_id in
        <foreach collection="occupancyIds" item="occupancyId" open="(" separator="," close=")">
          #{occupancyId}
        </foreach>
        order by occupancy_id, id
        for update
        </script>
        """)
    List<Long> findSeatIdsByOccupancyIdsForUpdate(@Param("occupancyIds") Collection<Long> occupancyIds);

    @Select("""
        <script>
        select distinct seat_allocation.seat_id
        from reservation_occupancy_seat seat_allocation
        join reservation_occupancy occupancy on occupancy.id = seat_allocation.occupancy_id
        where occupancy.space_id = #{spaceId}
          and occupancy.slot_date = #{slotDate}
          and occupancy.active_flag = 1
          and occupancy.start_time &lt; #{endTime}
          and occupancy.end_time &gt; #{startTime}
          <if test="excludedReservationId != null">
            and occupancy.reservation_id != #{excludedReservationId}
          </if>
          <if test="seatIds != null and !seatIds.isEmpty()">
            and seat_allocation.seat_id in
            <foreach collection="seatIds" item="seatId" open="(" separator="," close=")">
              #{seatId}
            </foreach>
          </if>
        </script>
        """)
    List<Long> findAllocatedSeatIds(@Param("spaceId") Long spaceId,
                                    @Param("slotDate") LocalDate slotDate,
                                    @Param("startTime") LocalTime startTime,
                                    @Param("endTime") LocalTime endTime,
                                    @Param("excludedReservationId") Long excludedReservationId,
                                    @Param("seatIds") Collection<Long> seatIds);
}
