package com.example.venue.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.inventory.entity.VenueBusinessCalendar;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

@Mapper
public interface VenueBusinessCalendarMapper extends BaseMapper<VenueBusinessCalendar> {
    @Select("""
        select *
        from venue_business_calendar
        where venue_id = #{venueId}
          and calendar_date = #{date}
          and deleted = 0
        limit 1
        """)
    VenueBusinessCalendar findByVenueAndDate(@Param("venueId") Long venueId, @Param("date") LocalDate date);
}
