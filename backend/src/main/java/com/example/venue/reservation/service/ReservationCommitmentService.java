package com.example.venue.reservation.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.entity.ReservationOccupancy;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.reservation.mapper.ReservationOccupancyMapper;
import com.example.venue.resource.mapper.ReservationOccupancySeatMapper;
import com.example.venue.security.SecurityContext;
import com.example.venue.venue.entity.Venue;
import com.example.venue.venue.entity.VenueSpace;
import com.example.venue.venue.enums.VenueResourceStatus;
import com.example.venue.venue.mapper.VenueSpaceMapper;
import com.example.venue.venue.service.VenueAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

@Service
public class ReservationCommitmentService {
    private static final Set<String> ELIGIBLE_STATUSES = Set.of(
        "APPROVED", "PENDING_PAYMENT", "PAID", "REFUNDING");

    private final ReservationMapper reservationMapper;
    private final ReservationOccupancyMapper occupancyMapper;
    private final ReservationOccupancySeatMapper occupancySeatMapper;
    private final VenueAccessService venueAccessService;
    private final VenueSpaceMapper spaceMapper;

    public ReservationCommitmentService(ReservationMapper reservationMapper,
                                        ReservationOccupancyMapper occupancyMapper,
                                        ReservationOccupancySeatMapper occupancySeatMapper,
                                        VenueAccessService venueAccessService,
                                        VenueSpaceMapper spaceMapper) {
        this.reservationMapper = reservationMapper;
        this.occupancyMapper = occupancyMapper;
        this.occupancySeatMapper = occupancySeatMapper;
        this.venueAccessService = venueAccessService;
        this.spaceMapper = spaceMapper;
    }

    @Transactional
    public CommitmentSnapshot validateForCommunity(Long reservationId,
                                                   Long organizerUserId,
                                                   Long venueId,
                                                   Long spaceId,
                                                   LocalDateTime startTime,
                                                   LocalDateTime endTime,
                                                   int promisedParticipants) {
        if (reservationId == null) {
            throw BusinessException.badRequest("地点确认必须关联预约");
        }
        CurrentUser current = SecurityContext.currentUser();
        if (current == null || current.id() == null || !current.id().equals(organizerUserId)) {
            throw BusinessException.forbidden("无权关联该预约");
        }
        Reservation reservation = reservationMapper.selectByIdForUpdate(reservationId);
        if (reservation == null || reservation.deleted != null && reservation.deleted == 1) {
            throw BusinessException.notFound("预约不存在");
        }
        if (!organizerUserId.equals(reservation.userId)) {
            venueAccessService.requireVenueManageAccess(reservation.venueId);
        }
        Venue venue = spaceMapper.findVenueBySpaceIdForUpdate(reservation.spaceId);
        VenueSpace space = spaceMapper.selectByIdForUpdate(reservation.spaceId);
        if (venue == null || space == null || !reservation.venueId.equals(venue.id)
            || !reservation.venueId.equals(space.venueId)
            || !VenueResourceStatus.isActive(venue.status)
            || !VenueResourceStatus.isActive(space.status)) {
            throw BusinessException.conflict("预约场馆或空间当前不可用于活动地点确认");
        }
        ReservationOccupancy occupancy = occupancyMapper.selectActiveByReservationIdForUpdate(reservation.id);
        CommitmentSnapshot snapshot = inspect(reservation, occupancy);
        if (!snapshot.eligible()) {
            throw BusinessException.conflict(messageFor(snapshot.reasonCode()));
        }
        if (!reservation.venueId.equals(venueId) || !reservation.spaceId.equals(spaceId)) {
            throw BusinessException.badRequest("活动地点与预约场馆空间不一致");
        }
        if (startTime == null || endTime == null
            || startTime.isBefore(reservation.startTime) || endTime.isAfter(reservation.endTime)) {
            throw BusinessException.conflict("预约时段不能覆盖活动承诺");
        }
        if (promisedParticipants < 1 || snapshot.reservedParticipants() < promisedParticipants) {
            throw BusinessException.conflict("预约人数或座位不能覆盖活动承诺");
        }
        return snapshot;
    }

    @Transactional(readOnly = true)
    public CommitmentSnapshot inspect(Long reservationId) {
        if (reservationId == null) {
            return CommitmentSnapshot.ineligible(null, "RESERVATION_NOT_FOUND");
        }
        Reservation reservation = reservationMapper.selectById(reservationId);
        if (reservation == null || reservation.deleted != null && reservation.deleted == 1) {
            return CommitmentSnapshot.ineligible(reservationId, "RESERVATION_NOT_FOUND");
        }
        return inspect(reservation, occupancyMapper.selectActiveByReservationId(reservation.id));
    }

    private CommitmentSnapshot inspect(Reservation reservation, ReservationOccupancy occupancy) {
        int participants = reservedParticipants(reservation, occupancy);
        String reason = null;
        if (!ELIGIBLE_STATUSES.contains(reservation.status)) {
            reason = "RESERVATION_STATUS_INELIGIBLE";
        } else if (reservation.endTime == null || !reservation.endTime.isAfter(LocalDateTime.now())) {
            reason = "RESERVATION_EXPIRED";
        } else if (occupancy == null) {
            reason = "RESERVATION_OCCUPANCY_RELEASED";
        }
        return new CommitmentSnapshot(
            reservation.id,
            reservation.userId,
            reservation.venueId,
            reservation.spaceId,
            reservation.startTime,
            reservation.endTime,
            participants,
            reservation.bookingModeSnapshot,
            reason == null,
            reason
        );
    }

    private int reservedParticipants(Reservation reservation, ReservationOccupancy occupancy) {
        int participantSnapshot = nonNegative(reservation.participantCount);
        int quantitySnapshot = occupancy == null ? 0 : nonNegative(occupancy.quantity);
        String bookingMode = reservation.bookingModeSnapshot != null
            ? reservation.bookingModeSnapshot
            : occupancy == null ? null : occupancy.bookingMode;
        if ("CAPACITY".equals(bookingMode)) {
            return Math.min(participantSnapshot, quantitySnapshot);
        }
        if ("ASSIGNED_SEAT".equals(bookingMode)) {
            int activeSeatCount = occupancy == null
                ? 0
                : occupancySeatMapper.findActiveSeatIdsByReservationId(reservation.id).size();
            return Math.min(Math.min(participantSnapshot, quantitySnapshot), activeSeatCount);
        }
        return participantSnapshot > 0 ? participantSnapshot : quantitySnapshot;
    }

    private int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(value, 0);
    }

    private String messageFor(String reasonCode) {
        return switch (reasonCode) {
            case "RESERVATION_STATUS_INELIGIBLE" -> "预约状态不能用于地点确认";
            case "RESERVATION_EXPIRED" -> "预约已过期，不能用于地点确认";
            case "RESERVATION_OCCUPANCY_RELEASED" -> "预约资源已释放，不能用于地点确认";
            default -> "预约不能用于地点确认";
        };
    }

    public record CommitmentSnapshot(
        Long reservationId,
        Long ownerUserId,
        Long venueId,
        Long spaceId,
        LocalDateTime startTime,
        LocalDateTime endTime,
        int reservedParticipants,
        String bookingMode,
        boolean eligible,
        String reasonCode
    ) {
        private static CommitmentSnapshot ineligible(Long reservationId, String reasonCode) {
            return new CommitmentSnapshot(
                reservationId, null, null, null, null, null, 0, null, false, reasonCode);
        }

        public boolean covers(Long expectedVenueId,
                              Long expectedSpaceId,
                              LocalDateTime expectedStart,
                              LocalDateTime expectedEnd,
                              int expectedParticipants) {
            return eligible
                && venueId != null && venueId.equals(expectedVenueId)
                && spaceId != null && spaceId.equals(expectedSpaceId)
                && startTime != null && endTime != null
                && !expectedStart.isBefore(startTime)
                && !expectedEnd.isAfter(endTime)
                && reservedParticipants >= expectedParticipants;
        }
    }
}
