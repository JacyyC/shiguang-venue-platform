package com.example.venue.reservation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.dto.ReservationVenueName;
import com.example.venue.reservation.enums.ReservationStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Mapper
public interface ReservationMapper extends BaseMapper<Reservation> {
    @Select({
        "<script>",
        "select r.id as reservation_id, r.venue_id, v.name as venue_name",
        "from reservation r left join venue v on v.id = r.venue_id and v.deleted = 0",
        "where r.id in",
        "<foreach collection='reservationIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
        "</script>"
    })
    List<ReservationVenueName> findVenueNamesByReservationIds(@Param("reservationIds") Collection<Long> reservationIds);
    @Select("select * from reservation where id = #{id} and deleted = 0 for update")
    Reservation selectByIdForUpdate(@Param("id") Long id);

    @Select("select exists(select 1 from reservation where space_id = #{spaceId} and deleted = 0)")
    boolean existsBySpaceId(@Param("spaceId") Long spaceId);

    // AUD-P2-04：CAS 占位活动预算回补标记。返回 1 表示首次占位成功（可执行回补），
    // 返回 0 表示已回补（budget_released=1），调用方幂等跳过，防双终态路径重复回补。
    @Update("update reservation set budget_released = 1 where id = #{id} and budget_released = 0 and deleted = 0")
    int markBudgetReleased(@Param("id") Long id);

    @Select("""
        <script>
        select *
        from reservation
        where space_id = #{spaceId}
          and deleted = 0
          and status in
          <foreach collection="statuses" item="status" open="(" separator="," close=")">
            #{status}
          </foreach>
          and start_time &lt; #{endTime}
          and end_time &gt; #{startTime}
        </script>
        """)
    List<Reservation> findOverlapsByStatuses(@Param("spaceId") Long spaceId,
                                             @Param("startTime") LocalDateTime startTime,
                                             @Param("endTime") LocalDateTime endTime,
                                             @Param("statuses") Collection<String> statuses);

    default List<Reservation> findActiveOverlaps(Long spaceId, LocalDateTime startTime, LocalDateTime endTime) {
        return findOverlapsByStatuses(spaceId, startTime, endTime, ReservationStatus.occupyingStatusNames());
    }

    @Select("""
        <script>
        select *
        from reservation
        where venue_id = #{venueId}
          and deleted = 0
          and status in
          <foreach collection="statuses" item="status" open="(" separator="," close=")">
            #{status}
          </foreach>
          and start_time &lt; #{endTime}
          and end_time &gt; #{startTime}
        </script>
        """)
    List<Reservation> findVenueOverlapsByStatuses(@Param("venueId") Long venueId,
                                                  @Param("startTime") LocalDateTime startTime,
                                                  @Param("endTime") LocalDateTime endTime,
                                                  @Param("statuses") Collection<String> statuses);
}
