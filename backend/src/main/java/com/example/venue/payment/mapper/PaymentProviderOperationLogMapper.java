package com.example.venue.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.payment.entity.PaymentProviderOperationLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PaymentProviderOperationLogMapper extends BaseMapper<PaymentProviderOperationLog> {
}
