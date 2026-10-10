package com.example.venue.notice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.notice.entity.NoticeDeliveryBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface NoticeDeliveryBatchMapper extends BaseMapper<NoticeDeliveryBatch> {
    @Select("select * from notice_delivery_batch where batch_no = #{batchNo} and deleted = 0 limit 1")
    NoticeDeliveryBatch findByBatchNo(@Param("batchNo") String batchNo);
}
