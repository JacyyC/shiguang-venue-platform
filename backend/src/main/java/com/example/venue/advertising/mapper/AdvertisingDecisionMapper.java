package com.example.venue.advertising.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.advertising.dto.AdvertisingDecisionCandidate;
import com.example.venue.advertising.entity.AdvertisingDecision;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AdvertisingDecisionMapper extends BaseMapper<AdvertisingDecision> {
    @Select("""
        SELECT
          a.id AS advertiser_id,
          c.id AS campaign_id,
          c.campaign_no,
          c.effective_from AS campaign_effective_from,
          c.effective_until AS campaign_effective_until,
          cv.id AS creative_version_id,
          cv.creative_no,
          cv.version_no,
          cv.headline,
          cv.body_text,
          cv.call_to_action,
          cv.target_type,
          cv.target_id
        FROM advertising_advertiser a
        JOIN advertising_campaign c ON c.advertiser_id = a.id
        JOIN advertising_creative_version cv ON cv.campaign_id = c.id
        WHERE a.status = 'ACTIVE'
          AND c.status = 'PUBLISHED'
          AND cv.status = 'PUBLISHED'
          AND c.effective_from <= #{now}
          AND c.effective_until > #{now}
        ORDER BY c.effective_from DESC, c.id ASC, cv.version_no DESC, cv.id ASC
        LIMIT #{limit}
        """)
    @Results(id = "advertisingDecisionCandidate", value = {
        @Result(property = "advertiserId", column = "advertiser_id"),
        @Result(property = "campaignId", column = "campaign_id"),
        @Result(property = "campaignNo", column = "campaign_no"),
        @Result(property = "campaignEffectiveFrom", column = "campaign_effective_from"),
        @Result(property = "campaignEffectiveUntil", column = "campaign_effective_until"),
        @Result(property = "creativeVersionId", column = "creative_version_id"),
        @Result(property = "creativeNo", column = "creative_no"),
        @Result(property = "versionNo", column = "version_no"),
        @Result(property = "headline", column = "headline"),
        @Result(property = "bodyText", column = "body_text"),
        @Result(property = "callToAction", column = "call_to_action"),
        @Result(property = "targetType", column = "target_type"),
        @Result(property = "targetId", column = "target_id")
    })
    List<AdvertisingDecisionCandidate> findPublishedCandidates(@Param("now") LocalDateTime now,
                                                               @Param("limit") int limit);

    @Select("""
        SELECT
          a.id AS advertiser_id,
          c.id AS campaign_id,
          c.campaign_no,
          c.effective_from AS campaign_effective_from,
          c.effective_until AS campaign_effective_until,
          cv.id AS creative_version_id,
          cv.creative_no,
          cv.version_no,
          cv.headline,
          cv.body_text,
          cv.call_to_action,
          cv.target_type,
          cv.target_id
        FROM advertising_advertiser a
        JOIN advertising_campaign c ON c.advertiser_id = a.id
        JOIN advertising_creative_version cv ON cv.campaign_id = c.id
        WHERE cv.id = #{creativeVersionId}
          AND a.status = 'ACTIVE'
          AND c.status = 'PUBLISHED'
          AND cv.status = 'PUBLISHED'
          AND c.effective_from <= #{now}
          AND c.effective_until > #{now}
        LIMIT 1
        FOR UPDATE
        """)
    @Results(value = {
        @Result(property = "advertiserId", column = "advertiser_id"),
        @Result(property = "campaignId", column = "campaign_id"),
        @Result(property = "campaignNo", column = "campaign_no"),
        @Result(property = "campaignEffectiveFrom", column = "campaign_effective_from"),
        @Result(property = "campaignEffectiveUntil", column = "campaign_effective_until"),
        @Result(property = "creativeVersionId", column = "creative_version_id"),
        @Result(property = "creativeNo", column = "creative_no"),
        @Result(property = "versionNo", column = "version_no"),
        @Result(property = "headline", column = "headline"),
        @Result(property = "bodyText", column = "body_text"),
        @Result(property = "callToAction", column = "call_to_action"),
        @Result(property = "targetType", column = "target_type"),
        @Result(property = "targetId", column = "target_id")
    })
    AdvertisingDecisionCandidate findPublishedCandidateForUpdate(
        @Param("creativeVersionId") Long creativeVersionId,
        @Param("now") LocalDateTime now);

    @Select("""
        SELECT * FROM advertising_decision
        WHERE user_id = #{userId}
          AND slot_code = #{slotCode}
          AND creative_version_id = #{creativeVersionId}
          AND status = 'ISSUED'
          AND expires_at > #{now}
        LIMIT 1 FOR UPDATE
        """)
    AdvertisingDecision findReusableIssuedForUpdate(@Param("userId") Long userId,
                                                    @Param("slotCode") String slotCode,
                                                    @Param("creativeVersionId") Long creativeVersionId,
                                                    @Param("now") LocalDateTime now);

    @Select("""
        SELECT * FROM advertising_decision
        WHERE token_hash = #{tokenHash}
        LIMIT 1
        """)
    AdvertisingDecision findByTokenHash(@Param("tokenHash") String tokenHash);

    @Select("""
        SELECT * FROM advertising_decision
        WHERE token_hash = #{tokenHash}
        LIMIT 1 FOR UPDATE
        """)
    AdvertisingDecision findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Update("""
        UPDATE advertising_decision
        SET status = 'CONSUMED', consumed_at = #{now}
        WHERE id = #{id} AND status = 'ISSUED'
        """)
    int markConsumed(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE advertising_decision
        SET token_hash = #{tokenHash}
        WHERE id = #{id} AND status = 'ISSUED' AND expires_at > #{now}
        """)
    int rotateIssuedTokenHash(@Param("id") Long id,
                              @Param("tokenHash") String tokenHash,
                              @Param("now") LocalDateTime now);
}
