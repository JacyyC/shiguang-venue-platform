package com.example.venue.notice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.notice.entity.NoticeExperiment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface NoticeExperimentMapper extends BaseMapper<NoticeExperiment> {
    @Select("select * from notice_experiment where experiment_no = #{experimentNo} and deleted = 0 limit 1")
    NoticeExperiment findByExperimentNo(@Param("experimentNo") String experimentNo);
}
