package com.example.venue.feedback.dto;

import java.time.LocalDateTime;

public class PublicReviewResponse {
    public Long id;
    public String reviewNo;
    public Long venueId;
    public Long spaceId;
    public Integer overallRating;
    public Integer venueRating;
    public Integer spaceRating;
    public Integer serviceRating;
    public String tagsJson;
    public String contentSanitized;
    public Integer sensitiveFlag;
    public LocalDateTime submittedAt;
}
