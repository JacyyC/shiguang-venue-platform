package com.example.venue.venue.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.venue.entity.VenueFacilityMaintenance;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface VenueFacilityMaintenanceMapper extends BaseMapper<VenueFacilityMaintenance> {
    @Select("""
        select *
        from venue_facility_maintenance
        where status = 'ACTIVE'
          and (space_id = #{spaceId} or space_id is null)
          and start_time < #{endTime}
          and end_time > #{startTime}
        """)
    List<VenueFacilityMaintenance> findActiveOverlaps(@Param("spaceId") Long spaceId,
                                                      @Param("startTime") LocalDateTime startTime,
                                                      @Param("endTime") LocalDateTime endTime);

    @Select("""
        select *
        from venue_facility_maintenance
        where unavailable_block_id = #{blockId}
        order by id desc
        limit 1
        """)
    VenueFacilityMaintenance findByUnavailableBlockId(@Param("blockId") Long blockId);
}
