package com.example.venue.placement.source;

/**
 * 窄只读 Source Reader 合同。每个实现留在来源领域，通过现有领域 Service/Mapper 判定来源状态
 * 和当前用户可见性。宣传 Service 禁止直接注入 NoticeMapper/PromotionCampaignMapper/
 * CommunityActivityMapper 或社区治理 Mapper。
 *
 * <ul>
 *   <li>{@link #readForPreview} 在候选引用/发布时按来源状态复核，返回白名单快照供运营预览，
 *       不校验目标用户受众或社区可见性；来源未发布/已下线/已结束返回 null。</li>
 *   <li>{@link #readForUser} 在每次新决策时按目标登录用户再次复核来源状态与当前用户可见性，
 *       不可见统一返回 null，不泄漏是不存在、下线、组内限制还是双向屏蔽。</li>
 * </ul>
 */
public interface PlacementSourceReader {

    PlacementSourceType sourceType();

    /**
     * 按来源状态复核，供运营预览。不判目标用户受众或社区可见性。
     *
     * @return 来源有效时返回白名单快照；未发布/已下线/已结束返回 null。
     */
    PlacementSourceSnapshot readForPreview(Long sourceId);

    /**
     * 按目标登录用户复核来源状态与当前用户可见性。不可见统一返回 null。
     *
     * @return 来源对当前用户可见时返回白名单快照；否则 null。
     */
    PlacementSourceSnapshot readForUser(Long sourceId, Long userId);
}
