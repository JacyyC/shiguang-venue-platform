package com.example.venue.member.dto;

import java.math.BigDecimal;

public class MemberLevelResponse {
    public Long id;
    public String levelCode;
    public String levelName;
    public Integer growthThreshold;
    public BigDecimal discountRate;
    public String benefitText;
    public String ruleVersion;
    public String status;
}
