package com.example.venue.siteops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.siteops.entity.SiteIncidentLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SiteIncidentLogMapper extends BaseMapper<SiteIncidentLog> {
    // A26-P3-19：resolveIncident 用 FOR UPDATE 锁行，避免并发解决时先写的处理结论被整行覆盖。
    @Select("select * from site_incident_log where id = #{id} and deleted = 0 for update")
    SiteIncidentLog selectByIdForUpdate(@Param("id") Long id);
}
