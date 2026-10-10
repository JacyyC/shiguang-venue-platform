package com.example.venue.notice.dto;

import com.example.venue.notice.entity.Notice;

import java.time.LocalDateTime;

/**
 * Anonymous announcement payload. Administrative targeting and delivery fields
 * deliberately remain server-only.
 */
public class PublicNoticeResponse {
    public Long id;
    public String title;
    public String content;
    public LocalDateTime publishTime;

    public static PublicNoticeResponse from(Notice notice) {
        PublicNoticeResponse response = new PublicNoticeResponse();
        response.id = notice.id;
        response.title = notice.title;
        response.content = notice.content;
        response.publishTime = notice.publishTime;
        return response;
    }
}
