package com.example.venue.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.promotion.entity.UserCoupon;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface UserCouponMapper extends BaseMapper<UserCoupon> {
    @Select("select * from user_coupon where id = #{id} and deleted = 0 for update")
    UserCoupon selectByIdForUpdate(@Param("id") Long id);

    @Update("""
        update user_coupon
        set status = 'VOIDED', update_time = current_timestamp
        where id = #{id} and status = 'CLAIMED'
          and source_type = 'MEMBER_REWARD' and deleted = 0
        """)
    int voidClaimedRewardCoupon(@Param("id") Long id);

    @Update("""
        update user_coupon
        set status = 'LOCKED',
            reservation_id = #{reservationId},
            lock_token = #{lockToken},
            locked_at = current_timestamp,
            update_time = current_timestamp
        where id = #{id}
          and user_id = #{userId}
          and status = 'CLAIMED'
          and deleted = 0
          and (valid_start_time is null or valid_start_time <= current_timestamp)
          and (valid_end_time is null or valid_end_time >= current_timestamp)
        """)
    int lockIfClaimed(@Param("id") Long id,
                      @Param("userId") Long userId,
                      @Param("reservationId") Long reservationId,
                      @Param("lockToken") String lockToken);

    @Update("""
        update user_coupon
        set status = 'USED',
            order_id = #{orderId},
            discount_amount = #{discountAmount},
            used_at = current_timestamp,
            update_time = current_timestamp
        where id = #{id}
          and status = 'LOCKED'
          and deleted = 0
        """)
    int useIfLocked(@Param("id") Long id,
                    @Param("orderId") Long orderId,
                    @Param("discountAmount") BigDecimal discountAmount);

    @Update("""
        update user_coupon
        set status = 'CLAIMED',
            reservation_id = null,
            lock_token = null,
            locked_at = null,
            released_at = current_timestamp,
            metadata_json = json_set(coalesce(metadata_json, json_object()), '$.releaseReason', #{reason}),
            update_time = current_timestamp
        where id = #{id}
          and status = 'LOCKED'
          and deleted = 0
        """)
    int releaseIfLocked(@Param("id") Long id, @Param("reason") String reason);

    @Update("""
        update user_coupon
        set status = 'REFUNDED',
            refunded_at = current_timestamp,
            metadata_json = json_set(coalesce(metadata_json, json_object()), '$.refundReason', #{reason}),
            update_time = current_timestamp
        where id = #{id}
          and status = 'USED'
          and deleted = 0
        """)
    int markRefundedIfUsed(@Param("id") Long id, @Param("reason") String reason);

    @Select("""
        select count(*)
        from user_coupon
        where template_id = #{templateId}
          and user_id = #{userId}
          and deleted = 0
        """)
    long countUserCouponsForTemplate(@Param("templateId") Long templateId, @Param("userId") Long userId);
}
