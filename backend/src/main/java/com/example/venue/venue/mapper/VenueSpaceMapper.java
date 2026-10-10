package com.example.venue.venue.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.venue.dto.VenueSpaceAggregate;
import com.example.venue.venue.entity.VenueSpace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface VenueSpaceMapper extends BaseMapper<VenueSpace> {
    @Select("""
        <script>
        select venue_id as venueId,
               min(price_per_hour) as minPrice,
               count(*) as activeSpaceCount,
               group_concat(distinct type order by type separator ',') as spaceTypesCsv
        from venue_space
        where status = 'ACTIVE'
          and deleted = 0
          and venue_id in
          <foreach collection="venueIds" item="venueId" open="(" separator="," close=")">
            #{venueId}
          </foreach>
        group by venue_id
        </script>
        """)
    List<VenueSpaceAggregate> selectAggregatesByVenueIds(@Param("venueIds") List<Long> venueIds);

    @Select("""
        select distinct type from venue_space
        where deleted = 0 and status = 'ACTIVE'
          and type is not null and type != ''
        order by type
        """)
    List<String> selectDistinctSpaceTypes();

    @Select("select * from venue_space where id = #{id} and deleted = 0 for update")
    VenueSpace selectByIdForUpdate(@Param("id") Long id);

    @Select("select v.* from venue v join venue_space s on s.venue_id = v.id where s.id = #{spaceId} and v.deleted = 0 and s.deleted = 0")
    com.example.venue.venue.entity.Venue findVenueBySpaceId(@Param("spaceId") Long spaceId);

    @Select("""
        select v.*
        from venue v
        where v.id = (
            select s.venue_id from venue_space s where s.id = #{spaceId} and s.deleted = 0
        )
          and v.deleted = 0
        for update
        """)
    com.example.venue.venue.entity.Venue findVenueBySpaceIdForUpdate(@Param("spaceId") Long spaceId);

    // Allocation reads venue policy but mutates only the selected space/allocation.
    // Share the venue lock across spaces; administrative changes retain FOR UPDATE.
    @Select("""
        select v.*
        from venue v
        where v.id = (
            select s.venue_id from venue_space s where s.id = #{spaceId} and s.deleted = 0
        )
          and v.deleted = 0
        for share
        """)
    com.example.venue.venue.entity.Venue findVenueBySpaceIdForShare(@Param("spaceId") Long spaceId);

}
