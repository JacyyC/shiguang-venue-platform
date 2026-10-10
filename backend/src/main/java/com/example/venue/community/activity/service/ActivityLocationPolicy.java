package com.example.venue.community.activity.service;

import com.example.venue.common.BusinessException;
import com.example.venue.community.activity.dto.ActivityRequests;
import com.example.venue.community.service.CommunityTextPolicy;
import com.example.venue.reservation.service.ReservationCommitmentService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class ActivityLocationPolicy {
    private static final Pattern AREA_CODE = Pattern.compile("[A-Z0-9_]{2,64}");

    private final ReservationCommitmentService reservationCommitmentService;
    private final CommunityTextPolicy textPolicy;

    public ActivityLocationPolicy(ReservationCommitmentService reservationCommitmentService,
                                  CommunityTextPolicy textPolicy) {
        this.reservationCommitmentService = reservationCommitmentService;
        this.textPolicy = textPolicy;
    }

    public LocationDecision resolve(ActivityRequests.Location request,
                                    Long organizerUserId,
                                    LocalDateTime startTime,
                                    LocalDateTime endTime,
                                    int participantCapacity) {
        if (request == null || request.type == null) {
            throw BusinessException.badRequest("活动地点不能为空");
        }
        String type = request.type.trim().toUpperCase(Locale.ROOT);
        if ("CAMPUS_AREA".equals(type)) {
            if (request.reservationId != null || request.venueId != null || request.spaceId != null) {
                throw BusinessException.badRequest("粗粒度校区区域不能关联预约或场馆空间");
            }
            String areaCode = request.areaCode == null
                ? null : request.areaCode.trim().toUpperCase(Locale.ROOT);
            if (areaCode == null || !AREA_CODE.matcher(areaCode).matches()) {
                throw BusinessException.badRequest("校区区域代码不合法");
            }
            textPolicy.required(areaCode, 64, "校区区域");
            return new LocationDecision(type, null, null, areaCode, null, "UNCONFIRMED", 0L);
        }
        if (!"VENUE_SPACE".equals(type)) {
            throw BusinessException.badRequest("活动地点类型不合法");
        }
        if (request.venueId == null || request.spaceId == null || request.areaCode != null) {
            throw BusinessException.badRequest("场馆地点必须指定公开场馆和空间");
        }
        if (request.reservationId == null) {
            return new LocationDecision(type, request.venueId, request.spaceId, null,
                null, "UNCONFIRMED", 0L);
        }
        reservationCommitmentService.validateForCommunity(
            request.reservationId, organizerUserId, request.venueId, request.spaceId,
            startTime, endTime, participantCapacity);
        return new LocationDecision(type, request.venueId, request.spaceId, null,
            request.reservationId, "CONFIRMED", 1L);
    }

    public record LocationDecision(
        String type,
        Long venueId,
        Long spaceId,
        String areaCode,
        Long reservationId,
        String commitmentStatus,
        Long commitmentVersion
    ) {
    }
}
