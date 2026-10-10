package com.example.venue.checkin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.checkin.entity.ReservationLifecycle;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ReservationLifecycleMapper extends BaseMapper<ReservationLifecycle> {
    @Select("select * from reservation_lifecycle where reservation_id = #{reservationId} and deleted = 0 limit 1")
    ReservationLifecycle findByReservationId(@Param("reservationId") Long reservationId);

    @Select("select * from reservation_lifecycle where reservation_id = #{reservationId} and deleted = 0 for update")
    ReservationLifecycle findByReservationIdForUpdate(@Param("reservationId") Long reservationId);

    @Select("select * from reservation_lifecycle where checkin_code_hash = #{hash} and deleted = 0 limit 1")
    ReservationLifecycle findByCheckinCodeHash(@Param("hash") String hash);

    // AUD-P2-06 行锁查询，供自动爽约/人工核销在更新前锁定 lifecycle 行，
    // 避免与并发的人工核销/爽约标记互相覆盖当前事实
    @Select("select * from reservation_lifecycle where id = #{id} and deleted = 0 for update")
    ReservationLifecycle selectByIdForUpdate(@Param("id") Long id);

    @Select("""
        select * from reservation_lifecycle
        where deleted = 0
          and checkin_status = 'NOT_CHECKED_IN'
          and attendance_status = 'PENDING'
          and checkin_window_end < #{now}
        """)
    List<ReservationLifecycle> findUnverifiedAfterWindow(@Param("now") LocalDateTime now);
}
