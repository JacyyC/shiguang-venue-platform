package com.example.venue.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.order.entity.BizOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BizOrderMapper extends BaseMapper<BizOrder> {
    @Select("select * from biz_order where id = #{id} limit 1 for update")
    BizOrder selectByIdForUpdate(@Param("id") Long id);

    @Select("select * from biz_order where reservation_id = #{reservationId} limit 1")
    BizOrder findByReservationId(@Param("reservationId") Long reservationId);

    @Select("select * from biz_order where order_no = #{orderNo} limit 1")
    BizOrder findByOrderNo(@Param("orderNo") String orderNo);

    @Select("select * from biz_order where order_no = #{orderNo} limit 1 for update")
    BizOrder findByOrderNoForUpdate(@Param("orderNo") String orderNo);
}
