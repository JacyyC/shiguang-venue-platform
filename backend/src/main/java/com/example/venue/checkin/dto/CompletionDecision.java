package com.example.venue.checkin.dto;

public record CompletionDecision(boolean shouldComplete, String reasonText) {
    public static CompletionDecision noAction() {
        return new CompletionDecision(false, "等待核销或爽约确认");
    }

    public static CompletionDecision complete(String reasonText) {
        return new CompletionDecision(true, reasonText);
    }
}
