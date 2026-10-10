package com.example.venue.member.dto;

import java.math.BigDecimal;

public class MemberAccountResponse extends MemberAccountSummary {
    public BigDecimal discountRate;
    public String benefitText;
    public MemberLevelResponse nextLevel;
    public Integer nextLevelGrowthGap;
}
