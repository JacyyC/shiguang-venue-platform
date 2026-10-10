package com.example.venue.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.promotion.entity.CouponTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CouponTemplateMapper extends BaseMapper<CouponTemplate> {
    @Select("select * from coupon_template where id = #{id} and deleted = 0 for update")
    CouponTemplate selectByIdForUpdate(@Param("id") Long id);

    @Select("""
        select *
        from coupon_template
        where deleted = 0
          and status = 'ACTIVE'
          and (venue_id = #{venueId} or venue_id is null)
          and (space_id = #{spaceId} or space_id is null)
          and (claim_start_time is null or claim_start_time <= #{now})
          and (claim_end_time is null or claim_end_time >= #{now})
          and (total_quantity <= 0 or issued_quantity < total_quantity)
        order by sort_order asc, id desc
        """)
    List<CouponTemplate> findClaimable(@Param("venueId") Long venueId,
                                       @Param("spaceId") Long spaceId,
                                       @Param("now") LocalDateTime now);

    @Update("""
        update coupon_template
        set issued_quantity = issued_quantity + 1,
            update_time = current_timestamp
        where id = #{id}
          and deleted = 0
          and status = 'ACTIVE'
          and (total_quantity <= 0 or issued_quantity < total_quantity)
        """)
    int incrementIssuedIfAvailable(@Param("id") Long id);

    @Update("""
        update coupon_template
        set issued_quantity = issued_quantity + 1,
            update_time = current_timestamp
        where id = #{id}
          and deleted = 0
          and status = 'ACTIVE'
          and (total_quantity <= 0 or issued_quantity < total_quantity)
          and (
            select count(*)
            from user_coupon
            where template_id = #{id}
              and user_id = #{userId}
              and deleted = 0
          ) < #{perUserLimit}
        """)
    int incrementIssuedIfAvailableForUser(@Param("id") Long id,
                                          @Param("userId") Long userId,
                                          @Param("perUserLimit") Integer perUserLimit);

    @Update("""
        update coupon_template
        set used_quantity = used_quantity + 1,
            update_time = current_timestamp
        where id = #{id}
          and deleted = 0
        """)
    int incrementUsed(@Param("id") Long id);
}
