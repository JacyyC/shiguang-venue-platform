package com.example.venue.placement.profile;

/**
 * 会员等级受众窄读取合同。宣传域只通过此 SPI 读取当前用户的 levelCode，不直查会员 Mapper，
 * 也不读取画像标签。无会员账户时返回 null，决策按 ALL_AUTHENTICATED 受众放行。
 */
public interface PlacementMemberProfileReader {

    String currentLevelCode(Long userId);
}
