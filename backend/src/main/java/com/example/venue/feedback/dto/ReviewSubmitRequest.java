package com.example.venue.feedback.dto;

import java.util.List;

public class ReviewSubmitRequest {
    public Long venueId;
    public Long spaceId;
    public Long reservationId;
    public Long orderId;
    public Long lifecycleId;
    public Integer overallRating;
    public Integer venueRating;
    public Integer spaceRating;
    public Integer serviceRating;
    public List<String> tags;
    public String contentText;
}
