package com.example.venue.member.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MemberOverviewResponse {
    public Long totalMembers;
    public Long activeMembers;
    public Map<String, Long> levelDistribution = new LinkedHashMap<>();
    public List<MemberAccountSummary> coreUsers = new ArrayList<>();
    public List<MemberAccountSummary> dormantUsers = new ArrayList<>();
}
