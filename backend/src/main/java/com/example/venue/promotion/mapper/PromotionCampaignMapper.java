package com.example.venue.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.promotion.entity.PromotionCampaign;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PromotionCampaignMapper extends BaseMapper<PromotionCampaign> {
    @Select("""
        select *
        from promotion_campaign
        where deleted = 0
          and status = 'ACTIVE'
          and (venue_id = #{venueId} or venue_id is null)
          and (space_id = #{spaceId} or space_id is null)
          and (member_level_code = #{memberLevelCode} or member_level_code is null)
          and start_time < #{endTime}
          and end_time > #{startTime}
        order by priority desc, id desc
        """)
    List<PromotionCampaign> findActiveCampaigns(@Param("venueId") Long venueId,
                                                @Param("spaceId") Long spaceId,
                                                @Param("memberLevelCode") String memberLevelCode,
                                                @Param("startTime") LocalDateTime startTime,
                                                @Param("endTime") LocalDateTime endTime);

    @Update("""
        update promotion_campaign
        set budget_used_amount = coalesce(budget_used_amount, 0) + #{discountAmount},
            update_time = #{updateTime}
        where id = #{campaignId}
          and deleted = 0
          and status = 'ACTIVE'
          and (budget_amount is null
               or coalesce(budget_used_amount, 0) + #{discountAmount} <= budget_amount)
        """)
    int consumeBudgetIfAvailable(@Param("campaignId") Long campaignId,
                                 @Param("discountAmount") java.math.BigDecimal discountAmount,
                                 @Param("updateTime") LocalDateTime updateTime);

    // AUD-P2-04：原子回补活动折扣预算，用于改期等场景释放原预约占用的活动预算。
    // 不限制 status='ACTIVE'，原活动即使已切到 INACTIVE/ENDED 仍需把已用预算减回去；
    // deleted=0 防止对已逻辑删除的活动误操作；
    // budget_used_amount >= discountAmount 防止超额回补（也自然跳过 budget_amount=null 的无上限活动，
    // 因其 used 在创建路径未被 consume 累加，恒为 0）。
    @Update("""
        update promotion_campaign
        set budget_used_amount = coalesce(budget_used_amount, 0) - #{discountAmount},
            update_time = #{updateTime}
        where id = #{campaignId}
          and deleted = 0
          and coalesce(budget_used_amount, 0) >= #{discountAmount}
        """)
    int releaseBudgetIfAvailable(@Param("campaignId") Long campaignId,
                                 @Param("discountAmount") java.math.BigDecimal discountAmount,
                                 @Param("updateTime") LocalDateTime updateTime);
}
