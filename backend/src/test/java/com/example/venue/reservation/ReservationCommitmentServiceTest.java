package com.example.venue.reservation;

import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.ResultCode;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.entity.ReservationOccupancy;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.reservation.mapper.ReservationOccupancyMapper;
import com.example.venue.reservation.service.ReservationCommitmentService;
import com.example.venue.resource.mapper.ReservationOccupancySeatMapper;
import com.example.venue.venue.entity.Venue;
import com.example.venue.venue.entity.VenueSpace;
import com.example.venue.venue.mapper.VenueSpaceMapper;
import com.example.venue.venue.service.VenueAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationCommitmentServiceTest {
    @Mock
    private ReservationMapper reservationMapper;
    @Mock
    private ReservationOccupancyMapper occupancyMapper;
    @Mock
    private ReservationOccupancySeatMapper occupancySeatMapper;
    @Mock
    private VenueAccessService venueAccessService;
    @Mock
    private VenueSpaceMapper spaceMapper;

    private ReservationCommitmentService service;

    @BeforeEach
    void setUp() {
        service = new ReservationCommitmentService(
            reservationMapper, occupancyMapper, occupancySeatMapper, venueAccessService, spaceMapper);
        authenticate(7L, "USER");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void capacityCommitmentUsesTheConservativeParticipantAndOccupancySnapshots() {
        Reservation reservation = reservation("CAPACITY", 6);
        ReservationOccupancy occupancy = occupancy("CAPACITY", 4);
        stubLockedResource(reservation, occupancy);

        assertConflict(() -> service.validateForCommunity(
            reservation.id, 7L, reservation.venueId, reservation.spaceId,
            reservation.startTime, reservation.endTime, 5));

        verify(occupancySeatMapper, never()).findActiveSeatIdsByReservationId(reservation.id);
    }

    @Test
    void assignedSeatCommitmentCannotPromiseMoreThanActiveSeatFacts() {
        Reservation reservation = reservation("ASSIGNED_SEAT", 3);
        ReservationOccupancy occupancy = occupancy("ASSIGNED_SEAT", 3);
        stubLockedResource(reservation, occupancy);
        when(occupancySeatMapper.findActiveSeatIdsByReservationId(reservation.id))
            .thenReturn(List.of(101L, 102L));

        assertConflict(() -> service.validateForCommunity(
            reservation.id, 7L, reservation.venueId, reservation.spaceId,
            reservation.startTime, reservation.endTime, 3));

        ReservationCommitmentService.CommitmentSnapshot accepted = service.validateForCommunity(
            reservation.id, 7L, reservation.venueId, reservation.spaceId,
            reservation.startTime, reservation.endTime, 2);
        assertThat(accepted.reservedParticipants()).isEqualTo(2);
    }

    @Test
    void exclusiveCommitmentPreservesItsExplicitParticipantSnapshotInsteadOfAllocationQuantityOne() {
        Reservation reservation = reservation("EXCLUSIVE", 8);
        ReservationOccupancy occupancy = occupancy("EXCLUSIVE", 1);
        stubLockedResource(reservation, occupancy);

        ReservationCommitmentService.CommitmentSnapshot snapshot = service.validateForCommunity(
            reservation.id, 7L, reservation.venueId, reservation.spaceId,
            reservation.startTime.plusMinutes(15), reservation.endTime.minusMinutes(15), 8);

        assertThat(snapshot.bookingMode()).isEqualTo("EXCLUSIVE");
        assertThat(snapshot.reservedParticipants()).isEqualTo(8);
    }

    @Test
    void managerMayLinkAnotherUsersReservationOnlyThroughVenueAccessContract() {
        authenticate(9L, "VENUE_ADMIN");
        Reservation reservation = reservation("CAPACITY", 4);
        reservation.userId = 7L;
        ReservationOccupancy occupancy = occupancy("CAPACITY", 4);
        stubLockedResource(reservation, occupancy);

        ReservationCommitmentService.CommitmentSnapshot snapshot = service.validateForCommunity(
            reservation.id, 9L, reservation.venueId, reservation.spaceId,
            reservation.startTime, reservation.endTime, 4);

        assertThat(snapshot.ownerUserId()).isEqualTo(7L);
        verify(venueAccessService).requireVenueManageAccess(reservation.venueId);
    }

    @Test
    void locationConfirmationUsesReservationThenVenueSpaceThenOccupancyLockOrder() {
        Reservation reservation = reservation("CAPACITY", 4);
        ReservationOccupancy occupancy = occupancy("CAPACITY", 4);
        stubLockedResource(reservation, occupancy);

        service.validateForCommunity(
            reservation.id, 7L, reservation.venueId, reservation.spaceId,
            reservation.startTime, reservation.endTime, 4);

        InOrder order = inOrder(reservationMapper, spaceMapper, occupancyMapper);
        order.verify(reservationMapper).selectByIdForUpdate(reservation.id);
        order.verify(spaceMapper).findVenueBySpaceIdForUpdate(reservation.spaceId);
        order.verify(spaceMapper).selectByIdForUpdate(reservation.spaceId);
        order.verify(occupancyMapper).selectActiveByReservationIdForUpdate(reservation.id);
    }

    @Test
    void releasedOrExpiredReservationsNeverRemainEligible() {
        Reservation reservation = reservation("CAPACITY", 4);
        reservation.status = "CANCELED";
        when(reservationMapper.selectById(reservation.id)).thenReturn(reservation);

        ReservationCommitmentService.CommitmentSnapshot snapshot = service.inspect(reservation.id);

        assertThat(snapshot.eligible()).isFalse();
        assertThat(snapshot.reasonCode()).isEqualTo("RESERVATION_STATUS_INELIGIBLE");
    }

    private void stubLockedResource(Reservation reservation, ReservationOccupancy occupancy) {
        Venue venue = new Venue();
        venue.id = reservation.venueId;
        venue.status = "ACTIVE";
        VenueSpace space = new VenueSpace();
        space.id = reservation.spaceId;
        space.venueId = reservation.venueId;
        space.status = "ACTIVE";
        when(reservationMapper.selectByIdForUpdate(reservation.id)).thenReturn(reservation);
        when(spaceMapper.findVenueBySpaceIdForUpdate(reservation.spaceId)).thenReturn(venue);
        when(spaceMapper.selectByIdForUpdate(reservation.spaceId)).thenReturn(space);
        when(occupancyMapper.selectActiveByReservationIdForUpdate(reservation.id)).thenReturn(occupancy);
    }

    private Reservation reservation(String mode, int participants) {
        Reservation reservation = new Reservation();
        reservation.id = 101L;
        reservation.userId = 7L;
        reservation.venueId = 3L;
        reservation.spaceId = 5L;
        reservation.startTime = LocalDateTime.of(2099, 8, 1, 9, 0);
        reservation.endTime = LocalDateTime.of(2099, 8, 1, 11, 0);
        reservation.status = "PAID";
        reservation.deleted = 0;
        reservation.bookingModeSnapshot = mode;
        reservation.participantCount = participants;
        return reservation;
    }

    private ReservationOccupancy occupancy(String mode, int quantity) {
        ReservationOccupancy occupancy = new ReservationOccupancy();
        occupancy.id = 201L;
        occupancy.reservationId = 101L;
        occupancy.spaceId = 5L;
        occupancy.activeFlag = 1;
        occupancy.bookingMode = mode;
        occupancy.quantity = quantity;
        return occupancy;
    }

    private void authenticate(Long userId, String role) {
        CurrentUser user = new CurrentUser(userId, "user-" + userId, Set.of(role), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private void assertConflict(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOf(BusinessException.class)
            .extracting(error -> ((BusinessException) error).getCode())
            .isEqualTo(ResultCode.CONFLICT);
    }
}
