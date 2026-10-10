package com.example.venue.member.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class MemberAccountSummary {
    public Long userId;
    public String username;
    public String nickname;
    public Long memberAccountId;
    public String levelCode;
    public String levelName;
    public Integer growthValue;
    public Integer pointsBalance;
    public BigDecimal totalConsumptionAmount;
    public Integer totalReservations;
    public BigDecimal totalMemberDiscountAmount;
    public LocalDateTime lastActiveTime;
    public List<String> profileTags = new ArrayList<>();
}
