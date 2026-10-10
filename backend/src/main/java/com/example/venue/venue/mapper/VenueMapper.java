package com.example.venue.venue.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.venue.entity.Venue;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface VenueMapper extends BaseMapper<Venue> {
    @Select("select * from venue where id = #{id} and deleted = 0 for update")
    Venue selectByIdForUpdate(@Param("id") Long id);

    @Select("""
        select distinct area from venue
        where deleted = 0 and area is not null and area != ''
        order by area
        """)
    List<String> selectDistinctAreas();
}
