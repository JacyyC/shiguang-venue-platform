package com.example.venue.advertising.dto;

import java.time.LocalDateTime;

/**
 * 仅供服务端仲裁使用的已发布商业候选连接结果，不作为用户端 DTO 返回。
 */
public class AdvertisingDecisionCandidate {
    public Long advertiserId;
    public Long campaignId;
    public String campaignNo;
    public LocalDateTime campaignEffectiveFrom;
    public LocalDateTime campaignEffectiveUntil;
    public Long creativeVersionId;
    public String creativeNo;
    public Integer versionNo;
    public String headline;
    public String bodyText;
    public String callToAction;
    public String targetType;
    public Long targetId;
}
