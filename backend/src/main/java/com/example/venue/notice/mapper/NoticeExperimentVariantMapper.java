package com.example.venue.notice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.notice.entity.NoticeExperimentVariant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface NoticeExperimentVariantMapper extends BaseMapper<NoticeExperimentVariant> {
    @Select("""
        select * from notice_experiment_variant
        where experiment_id = #{experimentId}
        order by id asc
        """)
    List<NoticeExperimentVariant> findByExperimentId(@Param("experimentId") Long experimentId);
}
