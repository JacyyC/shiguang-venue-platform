package com.example.venue.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.inventory.entity.VenueUnavailableBlock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface VenueUnavailableBlockMapper extends BaseMapper<VenueUnavailableBlock> {
    @Select("""
        select *
        from venue_unavailable_block
        where venue_id = #{venueId}
          and deleted = 0
          and status = 'ACTIVE'
          and (space_id = #{spaceId} or space_id is null)
          and timestampadd(minute, -buffer_before_minutes, start_time) < #{endTime}
          and timestampadd(minute, buffer_after_minutes, end_time) > #{startTime}
        order by start_time, id
        """)
    List<VenueUnavailableBlock> findActiveOverlaps(@Param("venueId") Long venueId,
                                                   @Param("spaceId") Long spaceId,
                                                   @Param("startTime") LocalDateTime startTime,
                                                   @Param("endTime") LocalDateTime endTime);

    @Select("""
        select *
        from venue_unavailable_block
        where venue_id = #{venueId}
          and deleted = 0
          and status = 'ACTIVE'
          and timestampadd(minute, -buffer_before_minutes, start_time) < #{endTime}
          and timestampadd(minute, buffer_after_minutes, end_time) > #{startTime}
        order by start_time, id
        """)
    List<VenueUnavailableBlock> findActiveVenueOverlaps(@Param("venueId") Long venueId,
                                                        @Param("startTime") LocalDateTime startTime,
                                                        @Param("endTime") LocalDateTime endTime);

    /**
     * SHIGUANG-BATCH-02 S04：不可售记录列表（含 CANCELED）。
     * 资源面"维护与不可售记录"需要展示"已取消 · 保留记录"（预览 e/resources recordsView）；
     * 仅服务列表读取，槽位推导仍只消费 ACTIVE（dailyInventory/checkBookable 不变）。
     */
    @Select("""
        <script>
        select *
        from venue_unavailable_block
        where venue_id = #{venueId}
          and deleted = 0
          and status in ('ACTIVE', 'CANCELED')
          <if test="spaceId != null">
          and space_id = #{spaceId}
          </if>
          and timestampadd(minute, -buffer_before_minutes, start_time) &lt; #{endTime}
          and timestampadd(minute, buffer_after_minutes, end_time) &gt; #{startTime}
        order by start_time, id
        </script>
        """)
    List<VenueUnavailableBlock> findListedOverlaps(@Param("venueId") Long venueId,
                                                   @Param("spaceId") Long spaceId,
                                                   @Param("startTime") LocalDateTime startTime,
                                                   @Param("endTime") LocalDateTime endTime);
}
