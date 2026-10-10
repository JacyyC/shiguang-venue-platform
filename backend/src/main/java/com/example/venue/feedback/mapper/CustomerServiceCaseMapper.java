package com.example.venue.feedback.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.feedback.entity.CustomerServiceCase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CustomerServiceCaseMapper extends BaseMapper<CustomerServiceCase> {
    // AUD-P2-07：解决/状态变更前用 FOR UPDATE 锁行，避免并发下 case/complaint 状态错乱
    // DC-02：SQL 统一加 deleted = 0，FOR UPDATE 不锁已逻辑删除行，调用方再校验 deleted 作双保险
    @Select("select * from customer_service_case where id = #{id} and deleted = 0 for update")
    CustomerServiceCase selectByIdForUpdate(@Param("id") Long id);
}
