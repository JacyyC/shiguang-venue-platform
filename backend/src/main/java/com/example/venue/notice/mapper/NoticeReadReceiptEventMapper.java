package com.example.venue.notice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.notice.entity.NoticeReadReceiptEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface NoticeReadReceiptEventMapper extends BaseMapper<NoticeReadReceiptEvent> {
    @Select("select * from notice_read_receipt_event where idempotency_key = #{idempotencyKey} limit 1 for update")
    NoticeReadReceiptEvent findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);
}
