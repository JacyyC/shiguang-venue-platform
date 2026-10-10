package com.example.venue.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.promotion.entity.PriceRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Mapper
public interface PriceRuleMapper extends BaseMapper<PriceRule> {
    @Select("""
        select *
        from price_rule
        where deleted = 0
          and status = 'ACTIVE'
          and (venue_id = #{venueId} or venue_id is null)
          and (space_id = #{spaceId} or space_id is null)
          and (member_level_code = #{memberLevelCode} or member_level_code is null)
          and start_time < #{endTime}
          and end_time > #{startTime}
          and (
            day_of_week_mask is null
            or day_of_week_mask = ''
            or concat(',', replace(day_of_week_mask, ' ', ''), ',') like concat('%,', #{dayOfWeek}, ',%')
          )
          and (start_clock is null or start_clock <= #{startClock})
          and (end_clock is null or end_clock >= #{endClock})
        order by priority desc, id desc
        limit 1
        """)
    PriceRule findBestRule(@Param("venueId") Long venueId,
                           @Param("spaceId") Long spaceId,
                           @Param("memberLevelCode") String memberLevelCode,
                           @Param("startTime") LocalDateTime startTime,
                           @Param("endTime") LocalDateTime endTime,
                           @Param("dayOfWeek") int dayOfWeek,
                           @Param("startClock") LocalTime startClock,
                           @Param("endClock") LocalTime endClock);
}
