package com.example.venue.promotion.source;

import com.example.venue.member.entity.MemberAccount;
import com.example.venue.member.mapper.MemberAccountMapper;
import com.example.venue.placement.source.PlacementSourceReader;
import com.example.venue.placement.source.PlacementSourceSnapshot;
import com.example.venue.placement.source.PlacementSourceType;
import com.example.venue.promotion.entity.PromotionCampaign;
import com.example.venue.promotion.mapper.PromotionCampaignMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 价格活动域 Source Reader。来源合同：deleted=0、status=ACTIVE、start_time &lt;= now &lt; end_time。
 * readForUser 额外校验 member_level_code 为空或等于当前用户 levelCode；站内深链有 venue_id 时
 * 为 /venues/{venueId}，否则为 /venues。不返回预算、已用预算、内部 metadata 或任意 URL。
 */
@Component
public class PromotionPlacementSourceReader implements PlacementSourceReader {

    private static final int TITLE_MAX_LENGTH = 128;
    private static final int SUMMARY_MAX_LENGTH = 200;

    private final PromotionCampaignMapper campaignMapper;
    private final MemberAccountMapper memberAccountMapper;

    public PromotionPlacementSourceReader(PromotionCampaignMapper campaignMapper,
                                          MemberAccountMapper memberAccountMapper) {
        this.campaignMapper = campaignMapper;
        this.memberAccountMapper = memberAccountMapper;
    }

    @Override
    public PlacementSourceType sourceType() {
        return PlacementSourceType.PRICE_PROMOTION;
    }

    @Override
    public PlacementSourceSnapshot readForPreview(Long sourceId) {
        return read(sourceId, null, false);
    }

    @Override
    public PlacementSourceSnapshot readForUser(Long sourceId, Long userId) {
        return read(sourceId, userId, true);
    }

    private PlacementSourceSnapshot read(Long sourceId, Long userId, boolean enforceLevel) {
        if (sourceId == null || sourceId <= 0) {
            return null;
        }
        PromotionCampaign campaign = campaignMapper.selectById(sourceId);
        if (campaign == null
            || campaign.deleted != null && campaign.deleted == 1
            || !"ACTIVE".equals(campaign.status)) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        if (campaign.startTime == null || campaign.endTime == null
            || !campaign.startTime.isBefore(now)
            || !now.isBefore(campaign.endTime)) {
            return null;
        }
        if (enforceLevel && campaign.memberLevelCode != null && !campaign.memberLevelCode.isBlank()) {
            String userLevel = resolveLevelCode(userId);
            if (!campaign.memberLevelCode.equals(userLevel)) {
                return null;
            }
        }
        String deepLink = campaign.venueId != null
            ? "/venues/" + campaign.venueId
            : "/venues";
        return new PlacementSourceSnapshot(
            PlacementSourceType.PRICE_PROMOTION,
            campaign.id,
            versionOf(campaign),
            truncate(campaign.campaignName, TITLE_MAX_LENGTH),
            summarize(campaign),
            null,
            campaign.startTime,
            campaign.endTime,
            deepLink
        );
    }

    private String resolveLevelCode(Long userId) {
        if (userId == null) {
            return null;
        }
        MemberAccount account = memberAccountMapper.findByUserId(userId);
        return account == null ? null : account.levelCode;
    }

    private String summarize(PromotionCampaign campaign) {
        StringBuilder builder = new StringBuilder();
        if (campaign.campaignType != null && !campaign.campaignType.isBlank()) {
            builder.append(campaign.campaignType);
        }
        if (campaign.discountType != null && !campaign.discountType.isBlank()) {
            if (!builder.isEmpty()) {
                builder.append(" · ");
            }
            builder.append(campaign.discountType);
            if (campaign.discountValue != null) {
                builder.append(campaign.discountValue);
            }
        }
        if (builder.isEmpty() && campaign.campaignName != null) {
            builder.append(campaign.campaignName);
        }
        return truncate(builder.toString(), SUMMARY_MAX_LENGTH);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }

    private String versionOf(PromotionCampaign campaign) {
        LocalDateTime version = campaign.updateTime != null ? campaign.updateTime : campaign.createTime;
        return version == null ? null : version.toString();
    }
}
