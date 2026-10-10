package com.example.venue.reservation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.reservation.entity.ReservationCreateCommand;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ReservationCreateCommandMapper extends BaseMapper<ReservationCreateCommand> {
    /**
     * 幂等序列化读取：同键并发时等待首个事务提交后读取其结果。
     * 只会命中唯一索引范围内的一行，不会像缺行的 FOR UPDATE 那样产生间隙锁。
     */
    @Select("""
        select * from reservation_create_command
        where user_id = #{userId} and idempotency_key = #{idempotencyKey}
        limit 1 for update
        """)
    ReservationCreateCommand findForUpdate(@Param("userId") Long userId,
                                           @Param("idempotencyKey") String idempotencyKey);

    /** 只读结果核对入口：不加锁、不写入。 */
    @Select("""
        select * from reservation_create_command
        where user_id = #{userId} and idempotency_key = #{idempotencyKey}
        limit 1
        """)
    ReservationCreateCommand findForRead(@Param("userId") Long userId,
                                         @Param("idempotencyKey") String idempotencyKey);

    @Update("""
        update reservation_create_command
        set status = 'COMPLETED', reservation_id = #{reservationId}, result_status = #{resultStatus},
            completed_at = #{completedAt}
        where id = #{id} and status = 'PROCESSING'
        """)
    int complete(@Param("id") Long id,
                 @Param("reservationId") Long reservationId,
                 @Param("resultStatus") String resultStatus,
                 @Param("completedAt") LocalDateTime completedAt);
}
