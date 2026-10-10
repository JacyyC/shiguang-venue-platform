package com.example.venue.provider.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.provider.entity.VenueObservationLocation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface VenueObservationLocationMapper extends BaseMapper<VenueObservationLocation> {
    @Select("""
        SELECT *
        FROM venue_observation_location
        WHERE status = 'ACTIVE'
          AND deleted = 0
        ORDER BY id ASC
        """)
    List<VenueObservationLocation> selectActiveOrdered();
}
