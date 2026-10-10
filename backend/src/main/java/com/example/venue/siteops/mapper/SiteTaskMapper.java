package com.example.venue.siteops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.siteops.entity.SiteTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SiteTaskMapper extends BaseMapper<SiteTask> {
    // AUD-P2-01：完成/取消任务前用 FOR UPDATE 锁行，避免后写者覆盖先写终态。
    @Select("select * from site_task where id = #{id} and deleted = 0 for update")
    SiteTask selectByIdForUpdate(@Param("id") Long id);
}
