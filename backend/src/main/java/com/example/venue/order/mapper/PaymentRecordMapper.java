package com.example.venue.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.order.entity.PaymentRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PaymentRecordMapper extends BaseMapper<PaymentRecord> {
    @Select("select * from payment_record where transaction_id = #{transactionId} limit 1")
    PaymentRecord findByTransactionId(@Param("transactionId") String transactionId);
}
