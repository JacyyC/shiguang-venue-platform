package com.example.venue.resource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.resource.entity.VenueResourceSeat;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface VenueResourceSeatMapper extends BaseMapper<VenueResourceSeat> {
    @Select("""
        select * from venue_resource_seat
        where rule_id = #{ruleId} and status = 'ACTIVE'
        order by sort_order, id
        """)
    List<VenueResourceSeat> findActiveByRuleId(@Param("ruleId") Long ruleId);

    @Select("""
        <script>
        select * from venue_resource_seat
        where rule_id = #{ruleId}
          and status = 'ACTIVE'
          and id in
          <foreach collection="seatIds" item="seatId" open="(" separator="," close=")">
            #{seatId}
          </foreach>
        order by sort_order, id
        </script>
        """)
    List<VenueResourceSeat> findActiveByRuleIdAndIds(@Param("ruleId") Long ruleId,
                                                     @Param("seatIds") Collection<Long> seatIds);

    @Select("""
        <script>
        select * from venue_resource_seat
        where status = 'ACTIVE'
          and rule_id in
          <foreach collection="ruleIds" item="ruleId" open="(" separator="," close=")">
            #{ruleId}
          </foreach>
        order by rule_id, sort_order, id
        </script>
        """)
    List<VenueResourceSeat> findActiveByRuleIds(@Param("ruleIds") Collection<Long> ruleIds);
}
