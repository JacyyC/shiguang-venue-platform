package com.example.venue.feedback.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.feedback.dto.VenueRatingAggregate;
import com.example.venue.feedback.entity.VenueReview;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface VenueReviewMapper extends BaseMapper<VenueReview> {
    @Select("""
        <script>
        select venue_id as venueId,
               round(avg(overall_rating), 2) as averageRating,
               count(*) as reviewCount
        from venue_review
        where status = 'PUBLISHED'
          and deleted = 0
          and venue_id in
          <foreach collection="venueIds" item="venueId" open="(" separator="," close=")">
            #{venueId}
          </foreach>
        group by venue_id
        </script>
        """)
    List<VenueRatingAggregate> selectAggregatesByVenueIds(@Param("venueIds") List<Long> venueIds);
}
