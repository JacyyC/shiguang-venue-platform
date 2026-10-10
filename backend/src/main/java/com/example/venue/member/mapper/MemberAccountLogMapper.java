package com.example.venue.member.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.member.entity.MemberAccountLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MemberAccountLogMapper extends BaseMapper<MemberAccountLog> {
    @Select("""
        select *
        from member_account_log
        where event_type = #{eventType}
          and order_id = #{orderId}
        limit 1
        """)
    MemberAccountLog findByEventAndOrder(@Param("eventType") String eventType, @Param("orderId") Long orderId);

    @Select("""
        select *
        from member_account_log
        where business_key = #{businessKey}
        limit 1
        """)
    MemberAccountLog findByBusinessKey(@Param("businessKey") String businessKey);
}
