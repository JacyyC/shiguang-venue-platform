package com.example.venue.funnel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.funnel.entity.UserVenueFavorite;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserVenueFavoriteMapper extends BaseMapper<UserVenueFavorite> {
    @Select("""
        select * from user_venue_favorite
        where user_id = #{userId}
          and venue_id = #{venueId}
          and deleted = 0
        limit 1
        """)
    UserVenueFavorite findByUserAndVenue(@Param("userId") Long userId, @Param("venueId") Long venueId);
}
