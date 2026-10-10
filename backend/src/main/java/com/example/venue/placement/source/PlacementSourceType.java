package com.example.venue.placement.source;

/**
 * 自营宣传位可消费的来源类型。每类来源由所属领域的窄只读 Reader 提供，
 * 宣传域不直查来源 Mapper，也不回写来源状态、正文或预算。
 */
public enum PlacementSourceType {
    GLOBAL_NOTICE,
    PRICE_PROMOTION,
    COMMUNITY_ACTIVITY
}
