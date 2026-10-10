package com.example.venue.usertime.dto;

/**
 * 「我的时间」标签精确计数：服务端 SQL count，不取截断集合充当准确数。
 */
public record MeTimeCounts(long pendingApplications, long activeParticipations, long hosted) {
}
