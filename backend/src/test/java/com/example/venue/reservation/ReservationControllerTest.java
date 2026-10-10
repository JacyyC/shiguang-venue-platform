package com.example.venue.reservation;

import com.example.venue.checkin.dto.ReservationRescheduleResponse;
import com.example.venue.checkin.entity.ReservationRescheduleRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.venue.common.PageQuery;
import com.example.venue.common.PageResult;
import com.example.venue.common.Result;
import com.example.venue.member.dto.PricingPreviewResponse;
import com.example.venue.reservation.controller.ReservationController;
import com.example.venue.reservation.dto.AvailabilitySlot;
import com.example.venue.reservation.dto.CreateReservationRequest;
import com.example.venue.reservation.dto.ReservationQuery;
import com.example.venue.reservation.dto.ReservationQuoteRequest;
import com.example.venue.reservation.dto.ReservationSubmissionResult;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.service.ReservationService;
import com.example.venue.resource.dto.ResourceAvailabilityResponse;
import com.example.venue.resource.dto.ResourceRuleResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.validation.DataBinder;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationControllerTest {
    @Test
    void reservationContactValidationRejectsInvalidPhoneAndOverlongRemark() {
        CreateReservationRequest request = new CreateReservationRequest();
        request.spaceId = 10L;
        request.contactName = "王小明";
        request.contactPhone = "123";
        request.remark = "备".repeat(201);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            Set<ConstraintViolation<CreateReservationRequest>> violations = validator.validate(request);
            Map<String, String> messages = violations.stream().collect(Collectors.toMap(
                violation -> violation.getPropertyPath().toString(),
                ConstraintViolation::getMessage
            ));

            assertThat(messages).containsEntry("contactPhone", "请输入 11 位有效手机号");
            assertThat(messages).containsEntry("remark", "备注不能超过 200 个字符");

            request.contactPhone = "13800000000";
            request.remark = "备".repeat(200);
            assertThat(validator.validate(request)).isEmpty();
        }
    }

    @Test
    void myReservationQueryBindsStatusAndScopeParameters() {
        ReservationQuery query = new ReservationQuery();
        DataBinder binder = new DataBinder(query);

        binder.bind(new MutablePropertyValues(Map.of(
            "venueId", "5",
            "spaceId", "10",
            "status", "PENDING_PAYMENT",
            "date", "2026-08-18",
            "pageNum", "3",
            "pageSize", "25"
        )));

        assertThat(binder.getBindingResult().hasErrors()).isFalse();
        assertThat(query.venueId).isEqualTo(5L);
        assertThat(query.spaceId).isEqualTo(10L);
        assertThat(query.status).isEqualTo("PENDING_PAYMENT");
        assertThat(query.date).isEqualTo("2026-08-18");
        assertThat(query.getPageNum()).isEqualTo(3);
        assertThat(query.getPageSize()).isEqualTo(25);
    }

    @Test
    void resourceAvailabilityDelegatesSelectionToService() {
        ReservationService service = mock(ReservationService.class);
        ReservationController controller = new ReservationController(service);
        LocalDateTime start = LocalDateTime.parse("2026-08-10T10:00:00");
        LocalDateTime end = start.plusHours(2);
        ResourceAvailabilityResponse response = response(10L, "ASSIGNED_SEAT");
        when(service.resourceAvailability(10L, start, end, 2, List.of(501L, 502L)))
            .thenReturn(response);

        Result<ResourceAvailabilityResponse> result = controller.resourceAvailability(
            10L, start, end, 2, List.of(501L, 502L));

        assertThat(result.getData()).isSameAs(response);
        verify(service).resourceAvailability(10L, start, end, 2, List.of(501L, 502L));
    }

    @Test
    void quoteRequestValidatesFromSelectionFactsAloneWhileCreateStillRequiresContact() {
        ReservationQuoteRequest quote = new ReservationQuoteRequest();
        quote.spaceId = 10L;
        quote.startTime = LocalDateTime.parse("2026-08-10T10:00:00");
        quote.endTime = quote.startTime.plusHours(2);
        quote.participantCount = 2;
        quote.seatIds = List.of(501L, 502L);

        CreateReservationRequest create = new CreateReservationRequest();
        create.spaceId = 10L;

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(quote)).isEmpty();

            quote.spaceId = null;
            assertThat(validator.validate(quote)).extracting(violation -> violation.getPropertyPath().toString())
                .contains("spaceId");

            quote.spaceId = 10L;
            quote.participantCount = 0;
            assertThat(validator.validate(quote)).extracting(violation -> violation.getPropertyPath().toString())
                .contains("participantCount");

            // 创建路径的联系人必填不因本次新增只读端点而放松
            assertThat(validator.validate(create)).extracting(violation -> violation.getPropertyPath().toString())
                .contains("contactName", "contactPhone");
        }
    }

    @Test
    void readOnlyQuoteAndDetailEndpointsDelegateToService() {
        ReservationService service = mock(ReservationService.class);
        ReservationController controller = new ReservationController(service);
        ReservationQuoteRequest request = new ReservationQuoteRequest();
        PricingPreviewResponse preview = new PricingPreviewResponse();
        Reservation reservation = new Reservation();
        reservation.id = 99L;
        when(service.quote(request)).thenReturn(preview);
        when(service.myReservation(99L)).thenReturn(reservation);

        assertThat(controller.quote(request).getData()).isSameAs(preview);
        assertThat(controller.detail(99L).getData()).isSameAs(reservation);
        verify(service).quote(request);
        verify(service).myReservation(99L);
    }

    @Test
    void rescheduleEndpointsExposeAvailabilityAndUserFeedback() {
        ReservationService service = mock(ReservationService.class);
        ReservationController controller = new ReservationController(service);
        LocalDateTime start = LocalDateTime.parse("2026-08-11T10:00:00");
        LocalDateTime end = start.plusHours(1);
        ResourceAvailabilityResponse response = response(10L, "CAPACITY");
        LocalDate date = start.toLocalDate();
        AvailabilitySlot slot = new AvailabilitySlot();
        slot.spaceId = 10L;
        slot.startTime = start;
        slot.endTime = end;
        slot.status = "AVAILABLE";
        when(service.rescheduleSlots(99L, date)).thenReturn(List.of(slot));
        when(service.rescheduleAvailability(99L, start, end)).thenReturn(response);
        PageQuery query = new PageQuery();
        ReservationRescheduleResponse request = new ReservationRescheduleResponse(
            301L, 99L, 7L, 5L, 10L, start.minusDays(1), end.minusDays(1), start, end,
            "PENDING_REVIEW", "USER_REQUEST", "调整行程", null, null, start.minusHours(1), start.minusHours(1));
        PageResult<ReservationRescheduleResponse> page = PageResult.of(List.of(request), 1, 1, 10);
        when(service.myRescheduleRequests(query)).thenReturn(page);

        assertThat(controller.rescheduleSlots(99L, date.toString()).getData()).containsExactly(slot);
        assertThat(controller.rescheduleAvailability(99L, start, end).getData()).isSameAs(response);
        assertThat(controller.myRescheduleRequests(query).getData()).isSameAs(page);
        verify(service).rescheduleSlots(99L, date);
        verify(service).rescheduleAvailability(99L, start, end);
        verify(service).myRescheduleRequests(query);
    }

    @Test
    void rescheduleResponseDoesNotSerializePersistenceOrReviewerFields() {
        ReservationRescheduleRequest entity = new ReservationRescheduleRequest();
        entity.id = 301L;
        entity.reservationId = 99L;
        entity.userId = 7L;
        entity.status = "APPROVED";
        entity.reviewReason = "同意改期";
        entity.reviewedBy = 42L;
        entity.metadataJson = "{\"internal\":true}";
        entity.deleted = 0;

        JsonNode json = new ObjectMapper().findAndRegisterModules()
            .valueToTree(ReservationRescheduleResponse.from(entity));

        assertThat(json.path("id").asLong()).isEqualTo(301L);
        assertThat(json.path("reviewReason").asText()).isEqualTo("同意改期");
        assertThat(json.has("reviewedBy")).isFalse();
        assertThat(json.has("metadataJson")).isFalse();
        assertThat(json.has("deleted")).isFalse();
    }

    @Test
    void createForwardsTheIdempotencyKeyAndKeepsTheLegacyPathWithoutIt() {
        ReservationService service = mock(ReservationService.class);
        ReservationController controller = new ReservationController(service);
        CreateReservationRequest request = new CreateReservationRequest();
        Reservation reservation = new Reservation();
        reservation.id = 88L;
        when(service.create(request, "intent-key-0001")).thenReturn(reservation);
        when(service.create(request, null)).thenReturn(reservation);

        assertThat(controller.create(request, "intent-key-0001").getData()).isSameAs(reservation);
        assertThat(controller.create(request, null).getData()).isSameAs(reservation);
        verify(service).create(request, "intent-key-0001");
        verify(service).create(request, null);
    }

    @Test
    void submissionResultIsAReadOnlyLookupThatNeverCreatesAnything() {
        ReservationService service = mock(ReservationService.class);
        ReservationController controller = new ReservationController(service);
        Reservation completed = new Reservation();
        completed.id = 88L;
        completed.contactName = "王小明";
        completed.contactPhone = "13800000000";
        ReservationSubmissionResult result = ReservationSubmissionResult.completed(completed);
        when(service.submissionResult("intent-key-0001")).thenReturn(result);
        when(service.submissionResult(null)).thenReturn(ReservationSubmissionResult.none());

        Result<ReservationSubmissionResult> lookup = controller.submissionResult("intent-key-0001");
        assertThat(lookup.getData()).isSameAs(result);
        assertThat(lookup.getData().status).isEqualTo("COMPLETED");
        assertThat(controller.submissionResult(null).getData().status).isEqualTo("NONE");
        verify(service).submissionResult("intent-key-0001");
        verify(service).submissionResult(null);
        verify(service, never()).create(any(), any());
    }

    private ResourceAvailabilityResponse response(Long spaceId, String mode) {
        return new ResourceAvailabilityResponse(
            spaceId,
            new ResourceRuleResponse(8L, spaceId, "STUDY_ROOM", mode, 10,
                24, 2, "ACTIVE", null, List.of()),
            2,
            8,
            true,
            null,
            null
        );
    }
}
