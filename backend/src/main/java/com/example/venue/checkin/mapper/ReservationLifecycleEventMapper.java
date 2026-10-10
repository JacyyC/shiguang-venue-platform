package com.example.venue.checkin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.checkin.entity.ReservationLifecycleEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ReservationLifecycleEventMapper extends BaseMapper<ReservationLifecycleEvent> {
    @Select("""
        select * from reservation_lifecycle_event
        where reservation_id = #{reservationId}
        order by create_time desc, id desc
        limit #{limit}
        """)
    List<ReservationLifecycleEvent> findRecentByReservationId(@Param("reservationId") Long reservationId,
                                                              @Param("limit") int limit);
}
