package com.example.venue.resource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.resource.entity.VenueResourceRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Mapper
public interface VenueResourceRuleMapper extends BaseMapper<VenueResourceRule> {
    @Select("select * from venue_resource_rule where space_id = #{spaceId} and active_flag = 1 limit 1")
    VenueResourceRule selectActiveBySpaceId(@Param("spaceId") Long spaceId);

    @Select("select * from venue_resource_rule where space_id = #{spaceId} and active_flag = 1 limit 1 for update")
    VenueResourceRule selectActiveBySpaceIdForUpdate(@Param("spaceId") Long spaceId);

    @Select("""
        <script>
        select * from venue_resource_rule
        where active_flag = 1
          and space_id in
          <foreach collection="spaceIds" item="spaceId" open="(" separator="," close=")">
            #{spaceId}
          </foreach>
        </script>
        """)
    List<VenueResourceRule> selectActiveBySpaceIds(@Param("spaceIds") Collection<Long> spaceIds);

    @Select("select coalesce(max(version_no), 0) from venue_resource_rule where space_id = #{spaceId}")
    int selectMaxVersion(@Param("spaceId") Long spaceId);

    @Update("""
        update venue_resource_rule
        set status = 'RETIRED', active_flag = null, update_time = #{updateTime}
        where space_id = #{spaceId} and active_flag = 1
        """)
    int retireActive(@Param("spaceId") Long spaceId, @Param("updateTime") LocalDateTime updateTime);
}
