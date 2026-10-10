package com.example.venue.checkin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.checkin.entity.ReservationRescheduleRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReservationRescheduleRequestMapper extends BaseMapper<ReservationRescheduleRequest> {
    // A26-P3-03：改期申请审核行锁查询，供 reviewReschedule 在审核前锁定申请行，
    // 串行化并发"通过+拒绝"，避免"申请记为拒绝但预约已被改期"的矛盾终态。
    @Select("select * from reservation_reschedule_request where id = #{id} and deleted = 0 for update")
    ReservationRescheduleRequest selectByIdForUpdate(@Param("id") Long id);
}
