package com.example.venue.feedback;

import com.example.venue.common.PageResult;
import com.example.venue.feedback.controller.CustomerServiceAdminController;
import com.example.venue.feedback.controller.CustomerFeedbackController;
import com.example.venue.feedback.dto.ComplaintSubmitRequest;
import com.example.venue.feedback.dto.CustomerServiceCaseActionRequest;
import com.example.venue.feedback.dto.CustomerServiceQuery;
import com.example.venue.feedback.dto.FeedbackQuery;
import com.example.venue.feedback.dto.PublicReviewResponse;
import com.example.venue.feedback.dto.ReviewModerationRequest;
import com.example.venue.feedback.dto.ReviewSubmitRequest;
import com.example.venue.feedback.entity.CustomerComplaint;
import com.example.venue.feedback.entity.CustomerServiceCase;
import com.example.venue.feedback.entity.CustomerServiceCaseEvent;
import com.example.venue.feedback.entity.VenueReview;
import com.example.venue.feedback.service.CustomerFeedbackService;
import com.example.venue.feedback.service.FeedbackTextSanitizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({CustomerFeedbackController.class, CustomerServiceAdminController.class})
@ContextConfiguration(classes = {
    CustomerFeedbackController.class,
    CustomerServiceAdminController.class,
    FeedbackTextSanitizer.class,
    CustomerFeedbackControllerTest.MethodSecurityConfig.class
})
class CustomerFeedbackControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CustomerFeedbackService service;

    @Test
    void userFeedbackEndpointsRequireReservationMyAuthorityAndDelegate() throws Exception {
        VenueReview review = review(31L);
        CustomerComplaint complaint = complaint(41L);
        CustomerServiceCase serviceCase = serviceCase(51L);
        when(service.submitReview(any(ReviewSubmitRequest.class))).thenReturn(review);
        when(service.myReviews(any(FeedbackQuery.class))).thenReturn(PageResult.of(List.of(review), 1, 1, 10));
        when(service.submitComplaint(any(ComplaintSubmitRequest.class))).thenReturn(complaint);
        when(service.myComplaints(any(FeedbackQuery.class))).thenReturn(PageResult.of(List.of(complaint), 1, 1, 10));
        when(service.myCases(any(FeedbackQuery.class))).thenReturn(PageResult.of(List.of(serviceCase), 1, 1, 10));

        mockMvc.perform(post("/api/me/feedback/reviews")
                .with(reservationMyUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"venueId\":1,\"reservationId\":3,\"overallRating\":5,\"contentText\":\"很好\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(31));

        mockMvc.perform(get("/api/me/feedback/reviews")
                .with(reservationMyUser())
                .param("status", "PUBLISHED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.records[0].id").value(31));

        mockMvc.perform(post("/api/me/feedback/complaints")
                .with(reservationMyUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"venueId":1,"reservationId":3,"complaintType":"EQUIPMENT",
                     "severity":"HIGH","title":"灯光影响体验","contentText":"灯光故障"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(41));

        mockMvc.perform(get("/api/me/feedback/complaints")
                .with(reservationMyUser())
                .param("severity", "HIGH"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.records[0].id").value(41));

        mockMvc.perform(get("/api/me/feedback/cases")
                .with(reservationMyUser())
                .param("status", "OPEN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.records[0].id").value(51));

        verify(service).submitReview(any(ReviewSubmitRequest.class));
        verify(service).myReviews(any(FeedbackQuery.class));
        verify(service).submitComplaint(any(ComplaintSubmitRequest.class));
        verify(service).myComplaints(any(FeedbackQuery.class));
        verify(service).myCases(any(FeedbackQuery.class));
    }

    @Test
    void anonymousOrPermissionlessUsersCannotCallUserFeedbackEndpoints() throws Exception {
        mockMvc.perform(get("/api/me/feedback/reviews"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/me/feedback/reviews")
                .with(user("user").authorities(new SimpleGrantedAuthority("reservation:view")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"venueId\":1,\"overallRating\":5}"))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/me/feedback/complaints"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/me/feedback/complaints")
                .with(user("user").authorities(new SimpleGrantedAuthority("reservation:view")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"venueId\":1,\"complaintType\":\"EQUIPMENT\",\"severity\":\"HIGH\",\"title\":\"故障\"}"))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/me/feedback/cases"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/me/feedback/cases")
                .with(user("user").authorities(new SimpleGrantedAuthority("reservation:view"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void caseEventReadEndpointsApplyAuthorityAndReturnAudienceSpecificSanitizedDtos() throws Exception {
        CustomerServiceCaseEvent event = new CustomerServiceCaseEvent();
        event.id = 61L;
        event.eventNo = "CASE_EVT202607120001";
        event.caseId = 51L;
        event.complaintId = 41L;
        event.eventType = "REPLY_ADDED";
        event.fromStatus = "IN_PROGRESS";
        event.toStatus = "IN_PROGRESS";
        event.actorUserId = 8L;
        event.actorUsername = "venue_admin 13812345678";
        event.noteText = "客服原始回复 13812345678 ORD-20260712-0001";
        event.noteSanitized = "客服回复 13812345678 ORD-20260712-0001";
        event.internalNote = "内部备注 13812345678 PAY-20260712-0001";
        event.sourceType = "ADMIN";
        event.metadataJson = "{\"secret\":\"not-for-api\"}";
        event.occurredAt = LocalDateTime.of(2026, 7, 12, 10, 0);
        event.createTime = LocalDateTime.of(2026, 7, 12, 10, 1);

        when(service.myCaseEvents(51L)).thenReturn(List.of(event));
        when(service.adminCaseEvents(51L)).thenReturn(List.of(event));

        mockMvc.perform(get("/api/me/feedback/cases/51/events").with(reservationMyUser()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].eventType").value("REPLY_ADDED"))
            .andExpect(jsonPath("$.data[0].fromStatus").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data[0].toStatus").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data[0].occurredAt").value("2026-07-12T10:00:00"))
            .andExpect(jsonPath("$.data[0].noteSanitized")
                .value("客服回复 138****5678 [业务单号已隐藏]"))
            .andExpect(jsonPath("$.data[0].actorUsername").doesNotExist())
            .andExpect(jsonPath("$.data[0].sourceType").doesNotExist())
            .andExpect(jsonPath("$.data[0].internalNoteSanitized").doesNotExist())
            .andExpect(jsonPath("$.data[0].actorUserId").doesNotExist())
            .andExpect(jsonPath("$.data[0].eventNo").doesNotExist())
            .andExpect(jsonPath("$.data[0].caseId").doesNotExist())
            .andExpect(jsonPath("$.data[0].complaintId").doesNotExist())
            .andExpect(jsonPath("$.data[0].noteText").doesNotExist())
            .andExpect(jsonPath("$.data[0].internalNote").doesNotExist())
            .andExpect(jsonPath("$.data[0].metadataJson").doesNotExist())
            .andExpect(jsonPath("$.data[0].createTime").doesNotExist());

        mockMvc.perform(get("/api/admin/customer-service/cases/51/events")
                .with(jwtUser(8L, "venue_admin", "customer-service:manage")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].eventType").value("REPLY_ADDED"))
            .andExpect(jsonPath("$.data[0].fromStatus").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data[0].toStatus").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data[0].occurredAt").value("2026-07-12T10:00:00"))
            .andExpect(jsonPath("$.data[0].sourceType").value("ADMIN"))
            .andExpect(jsonPath("$.data[0].actorUsername").value("venue_admin 138****5678"))
            .andExpect(jsonPath("$.data[0].noteSanitized")
                .value("客服回复 138****5678 [业务单号已隐藏]"))
            .andExpect(jsonPath("$.data[0].internalNoteSanitized")
                .value("内部备注 138****5678 [业务单号已隐藏]"))
            .andExpect(jsonPath("$.data[0].actorUserId").doesNotExist())
            .andExpect(jsonPath("$.data[0].eventNo").doesNotExist())
            .andExpect(jsonPath("$.data[0].caseId").doesNotExist())
            .andExpect(jsonPath("$.data[0].complaintId").doesNotExist())
            .andExpect(jsonPath("$.data[0].noteText").doesNotExist())
            .andExpect(jsonPath("$.data[0].internalNote").doesNotExist())
            .andExpect(jsonPath("$.data[0].metadataJson").doesNotExist())
            .andExpect(jsonPath("$.data[0].createTime").doesNotExist());

        mockMvc.perform(get("/api/me/feedback/cases/51/events"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/me/feedback/cases/51/events")
                .with(user("user").authorities(new SimpleGrantedAuthority("reservation:view"))))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/customer-service/cases/51/events"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/customer-service/cases/51/events")
                .with(jwtUser(7L, "user", "reservation:my")))
            .andExpect(status().isForbidden());

        verify(service).myCaseEvents(51L);
        verify(service).adminCaseEvents(51L);
    }

    @Test
    void publicReviewEndpointsAllowAnonymousUsersAndDelegate() throws Exception {
        PublicReviewResponse review = publicReview(31L);
        when(service.publicReviewSummary(1L)).thenReturn(Map.of("venueId", 1L, "reviewCount", 1L));
        when(service.publicReviews(any(FeedbackQuery.class))).thenReturn(PageResult.of(List.of(review), 1, 1, 10));

        mockMvc.perform(get("/api/venues/1/reviews/summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.venueId").value(1))
            .andExpect(jsonPath("$.data.reviewCount").value(1));

        mockMvc.perform(get("/api/venues/1/reviews").param("pageNum", "1").param("pageSize", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.records[0].id").value(31))
            .andExpect(jsonPath("$.data.records[0].reviewNo").value("REVIEW202607060001"))
            .andExpect(jsonPath("$.data.records[0].venueId").value(1))
            .andExpect(jsonPath("$.data.records[0].spaceId").value(2))
            .andExpect(jsonPath("$.data.records[0].overallRating").value(5))
            .andExpect(jsonPath("$.data.records[0].venueRating").value(4))
            .andExpect(jsonPath("$.data.records[0].spaceRating").value(5))
            .andExpect(jsonPath("$.data.records[0].serviceRating").value(5))
            .andExpect(jsonPath("$.data.records[0].tagsJson").value("[\"CLEAN\"]"))
            .andExpect(jsonPath("$.data.records[0].contentSanitized").value("体验不错，138****5678"))
            .andExpect(jsonPath("$.data.records[0].sensitiveFlag").value(1))
            .andExpect(jsonPath("$.data.records[0].submittedAt").value("2026-07-06T10:00:00"))
            .andExpect(jsonPath("$.data.records[0].contentText").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].userId").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].reservationId").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].orderId").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].lifecycleId").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].hiddenReason").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].metadataJson").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].deleted").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].sourceType").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].createTime").doesNotExist())
            .andExpect(jsonPath("$.data.records[0].updateTime").doesNotExist());

        verify(service).publicReviewSummary(eq(1L));
        verify(service).publicReviews(any(FeedbackQuery.class));
    }

    @Test
    void publicReviewsBindVenueIdIntoQueryBeforeCallingService() throws Exception {
        when(service.publicReviews(any(FeedbackQuery.class))).thenReturn(PageResult.of(List.of(), 0, 1, 10));

        mockMvc.perform(get("/api/venues/7/reviews").param("status", "PUBLISHED"))
            .andExpect(status().isOk());

        verify(service).publicReviews(org.mockito.ArgumentMatchers.argThat((FeedbackQuery query) ->
            query.venueId.equals(7L) && "PUBLISHED".equals(query.status)));
    }

    @Test
    void b9JsonResponsesDoNotExposeRawPrivacyFieldsOrValues() throws Exception {
        VenueReview rawReview = review(31L);
        rawReview.reviewNo = "REVIEW202607060001";
        rawReview.orderId = 991L;
        rawReview.reservationId = 881L;
        rawReview.lifecycleId = 771L;
        rawReview.contentText = "原始评价 13812345678 ORDER202607060001";
        rawReview.contentSanitized = "原始评价 138****5678 ORD-202607060001";
        rawReview.hiddenReason = "含手机号 13812345678";
        rawReview.metadataJson = "{\"contact\":\"13812345678\"}";
        rawReview.submittedAt = LocalDateTime.of(2026, 7, 6, 10, 0);

        CustomerComplaint rawComplaint = complaint(41L);
        rawComplaint.complaintNo = "COMP202607060001";
        rawComplaint.orderId = 992L;
        rawComplaint.reservationId = 882L;
        rawComplaint.contentText = "原始投诉 13812345678 ORD-20260706-0001";
        rawComplaint.contentSanitized = "原始投诉 138****5678 REF202607060002";
        rawComplaint.requestedResolution = "请电话联系 13812345678";
        rawComplaint.metadataJson = "{\"paymentProof\":\"secret-proof\"}";
        rawComplaint.submittedAt = LocalDateTime.of(2026, 7, 6, 11, 0);

        CustomerServiceCase rawCase = serviceCase(51L);
        rawCase.caseNo = "CASE202607060001";
        rawCase.complaintId = rawComplaint.id;
        rawCase.resolutionSummary = "内部处理记录 13812345678 REFUND:20260706:0004";
        rawCase.customerVisibleReply = "客服回复 13812345678 REF202607060002";
        rawCase.metadataJson = "{\"internalNote\":\"完整内部处理\"}";

        CustomerServiceCaseEvent rawEvent = new CustomerServiceCaseEvent();
        rawEvent.id = 61L;
        rawEvent.eventNo = "CASE_EVT202607060001";
        rawEvent.caseId = rawCase.id;
        rawEvent.complaintId = rawComplaint.id;
        rawEvent.eventType = "REPLY_ADDED";
        rawEvent.noteText = "原始备注 13812345678 PAY_20260706_0003";
        rawEvent.noteSanitized = "原始备注 138****5678 PAY_202607060003";
        rawEvent.internalNote = "完整内部处理 secret-proof";
        rawEvent.metadataJson = "{\"paymentProof\":\"secret-proof\"}";
        rawEvent.occurredAt = LocalDateTime.of(2026, 7, 6, 12, 0);

        when(service.submitReview(any(ReviewSubmitRequest.class))).thenReturn(rawReview);
        when(service.myReviews(any(FeedbackQuery.class))).thenReturn(PageResult.of(List.of(rawReview), 1, 1, 10));
        when(service.submitComplaint(any(ComplaintSubmitRequest.class))).thenReturn(rawComplaint);
        when(service.myComplaints(any(FeedbackQuery.class))).thenReturn(PageResult.of(List.of(rawComplaint), 1, 1, 10));
        when(service.myCases(any(FeedbackQuery.class))).thenReturn(PageResult.of(List.of(rawCase), 1, 1, 10));
        when(service.publicReviews(any(FeedbackQuery.class))).thenReturn(PageResult.of(List.of(publicReview(31L)), 1, 1, 10));
        when(service.adminComplaints(any(CustomerServiceQuery.class))).thenReturn(PageResult.of(List.of(rawComplaint), 1, 1, 10));
        when(service.adminCases(any(CustomerServiceQuery.class))).thenReturn(PageResult.of(List.of(rawCase), 1, 1, 10));
        when(service.adminReviews(any(CustomerServiceQuery.class))).thenReturn(PageResult.of(List.of(rawReview), 1, 1, 10));
        when(service.assignCase(eq(51L), any(CustomerServiceCaseActionRequest.class))).thenReturn(rawCase);
        when(service.addReply(eq(51L), any(CustomerServiceCaseActionRequest.class))).thenReturn(rawEvent);
        when(service.resolveCase(eq(51L), any(CustomerServiceCaseActionRequest.class))).thenReturn(rawCase);
        when(service.moderateReview(eq(31L), any(ReviewModerationRequest.class))).thenReturn(rawReview);
        when(service.adminContext(any(CustomerServiceQuery.class))).thenReturn(Map.of(
            "complaints", List.of(Map.of("contentSanitized", "原始投诉 138****5678 [业务单号已隐藏]")),
            "cases", List.of(Map.of("status", "OPEN")),
            "reviews", List.of(Map.of("contentSanitized", "原始评价 138****5678 [业务单号已隐藏]"))
        ));

        List<String> bodies = new ArrayList<>();
        bodies.add(mockMvc.perform(post("/api/me/feedback/reviews")
                .with(reservationMyUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"venueId\":1,\"reservationId\":3,\"overallRating\":5,\"contentText\":\"很好\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        bodies.add(mockMvc.perform(get("/api/me/feedback/reviews").with(reservationMyUser()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        bodies.add(mockMvc.perform(post("/api/me/feedback/complaints")
                .with(reservationMyUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"venueId\":1,\"complaintType\":\"EQUIPMENT\",\"severity\":\"HIGH\",\"title\":\"灯光\",\"contentText\":\"故障\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        bodies.add(mockMvc.perform(get("/api/me/feedback/complaints").with(reservationMyUser()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        bodies.add(mockMvc.perform(get("/api/me/feedback/cases").with(reservationMyUser()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        bodies.add(mockMvc.perform(get("/api/venues/1/reviews"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        bodies.add(mockMvc.perform(get("/api/admin/customer-service/complaints")
                .with(jwtUser(8L, "venue_admin", "customer-service:manage")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        bodies.add(mockMvc.perform(get("/api/admin/customer-service/cases")
                .with(jwtUser(8L, "venue_admin", "customer-service:manage")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        bodies.add(mockMvc.perform(get("/api/admin/customer-service/reviews")
                .with(jwtUser(1L, "admin", "review:manage")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        bodies.add(mockMvc.perform(post("/api/admin/customer-service/cases/51/assign")
                .with(jwtUser(8L, "venue_admin", "customer-service:manage"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assignedUserId\":8}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        bodies.add(mockMvc.perform(post("/api/admin/customer-service/cases/51/reply")
                .with(jwtUser(8L, "venue_admin", "customer-service:manage"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"replyText\":\"已处理\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        bodies.add(mockMvc.perform(post("/api/admin/customer-service/cases/51/resolve")
                .with(jwtUser(8L, "venue_admin", "customer-service:manage"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resolutionType\":\"EXPLAINED\",\"replyText\":\"已处理\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        bodies.add(mockMvc.perform(post("/api/admin/customer-service/reviews/31/moderate")
                .with(jwtUser(1L, "admin", "review:manage"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"HIDE\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        bodies.add(mockMvc.perform(get("/api/admin/customer-service/context")
                .with(jwtUser(1L, "admin", "customer-service:analytics")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(String.join("\n", bodies))
            .contains("contentSanitized")
            .doesNotContain("contentText")
            .doesNotContain("noteText")
            .doesNotContain("internalNote")
            .doesNotContain("requestedResolution")
            .doesNotContain("resolutionSummary")
            .doesNotContain("metadataJson")
            .doesNotContain("userId")
            .doesNotContain("orderId")
            .doesNotContain("paymentProof")
            .doesNotContain("secret-proof")
            .doesNotContain("完整内部处理")
            .doesNotContain("13812345678")
            .doesNotContain("ORDER202607060001")
            .doesNotContain("ORD-202607060001")
            .doesNotContain("ORD-20260706-0001")
            .doesNotContain("REF202607060002")
            .doesNotContain("REFUND:20260706:0004")
            .doesNotContain("PAY_202607060003")
            .doesNotContain("PAY_20260706_0003");
    }

    @Test
    void adminCustomerServiceEndpointsRequirePermissionsAndDelegate() throws Exception {
        CustomerComplaint complaint = complaint(41L);
        CustomerServiceCase serviceCase = serviceCase(21L);
        VenueReview review = review(11L);
        when(service.adminComplaints(any(CustomerServiceQuery.class)))
            .thenReturn(PageResult.of(List.of(complaint), 1, 1, 10));
        when(service.assignCase(eq(21L), any(CustomerServiceCaseActionRequest.class))).thenReturn(serviceCase);
        when(service.resolveCase(eq(21L), any(CustomerServiceCaseActionRequest.class))).thenReturn(serviceCase);
        when(service.moderateReview(eq(11L), any(ReviewModerationRequest.class))).thenReturn(review);
        when(service.adminContext(any(CustomerServiceQuery.class))).thenReturn(Map.of("complaintCount", 1L));

        mockMvc.perform(get("/api/admin/customer-service/complaints")
                .with(jwtUser(8L, "venue_admin", "customer-service:manage")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.records[0].id").value(41));

        mockMvc.perform(post("/api/admin/customer-service/cases/21/assign")
                .with(jwtUser(8L, "venue_admin", "customer-service:manage"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assignedUserId\":8}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(21));

        mockMvc.perform(post("/api/admin/customer-service/cases/21/resolve")
                .with(jwtUser(8L, "venue_admin", "customer-service:manage"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resolutionType\":\"EXPLAINED\",\"replyText\":\"已处理\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(21));

        mockMvc.perform(post("/api/admin/customer-service/reviews/11/moderate")
                .with(jwtUser(1L, "admin", "review:manage"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"HIDE\",\"reasonCode\":\"SENSITIVE\",\"reasonText\":\"含敏感信息\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(11));

        mockMvc.perform(get("/api/admin/customer-service/context")
                .with(jwtUser(1L, "admin", "customer-service:analytics")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.complaintCount").value(1));

        verify(service).adminComplaints(any(CustomerServiceQuery.class));
        verify(service).assignCase(eq(21L), any(CustomerServiceCaseActionRequest.class));
        verify(service).resolveCase(eq(21L), any(CustomerServiceCaseActionRequest.class));
        verify(service).moderateReview(eq(11L), any(ReviewModerationRequest.class));
        verify(service).adminContext(any(CustomerServiceQuery.class));
    }

    @Test
    void adminCustomerServiceEndpointsRejectMissingPermissions() throws Exception {
        mockMvc.perform(get("/api/admin/customer-service/complaints")
                .with(jwtUser(8L, "venue_admin", "reservation:my")))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/customer-service/reviews/11/moderate")
                .with(jwtUser(1L, "admin", "customer-service:manage"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"HIDE\"}"))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/customer-service/context")
                .with(jwtUser(1L, "admin", "customer-service:manage")))
            .andExpect(status().isForbidden());
    }

    @Test
    void adminComplaintsBindQueryParametersBeforeCallingService() throws Exception {
        when(service.adminComplaints(any(CustomerServiceQuery.class)))
            .thenReturn(PageResult.of(List.of(), 0, 1, 10));

        mockMvc.perform(get("/api/admin/customer-service/complaints")
                .with(jwtUser(8L, "venue_admin", "customer-service:manage"))
                .param("venueId", "7")
                .param("spaceId", "9")
                .param("status", "SUBMITTED")
                .param("severity", "HIGH")
                .param("complaintType", "EQUIPMENT")
                .param("keyword", "灯光"))
            .andExpect(status().isOk());

        verify(service).adminComplaints(org.mockito.ArgumentMatchers.argThat((CustomerServiceQuery query) ->
            Long.valueOf(7L).equals(query.venueId)
                && Long.valueOf(9L).equals(query.spaceId)
                && "SUBMITTED".equals(query.status)
                && "HIGH".equals(query.severity)
                && "EQUIPMENT".equals(query.complaintType)
                && "灯光".equals(query.keyword)));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor reservationMyUser() {
        return user("user").authorities(new SimpleGrantedAuthority("reservation:my"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor jwtUser(Long userId,
                                                                                    String username,
                                                                                    String... authorities) {
        return user(username + "-" + userId)
            .authorities(Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toArray(SimpleGrantedAuthority[]::new));
    }

    private VenueReview review(Long id) {
        VenueReview review = new VenueReview();
        review.id = id;
        review.venueId = 1L;
        review.userId = 7L;
        review.status = "PUBLISHED";
        review.overallRating = 5;
        return review;
    }

    private PublicReviewResponse publicReview(Long id) {
        PublicReviewResponse review = new PublicReviewResponse();
        review.id = id;
        review.reviewNo = "REVIEW202607060001";
        review.venueId = 1L;
        review.spaceId = 2L;
        review.overallRating = 5;
        review.venueRating = 4;
        review.spaceRating = 5;
        review.serviceRating = 5;
        review.tagsJson = "[\"CLEAN\"]";
        review.contentSanitized = "体验不错，138****5678";
        review.sensitiveFlag = 1;
        review.submittedAt = java.time.LocalDateTime.of(2026, 7, 6, 10, 0, 0);
        return review;
    }

    private CustomerComplaint complaint(Long id) {
        CustomerComplaint complaint = new CustomerComplaint();
        complaint.id = id;
        complaint.venueId = 1L;
        complaint.userId = 7L;
        complaint.status = "SUBMITTED";
        complaint.severity = "HIGH";
        return complaint;
    }

    private CustomerServiceCase serviceCase(Long id) {
        CustomerServiceCase serviceCase = new CustomerServiceCase();
        serviceCase.id = id;
        serviceCase.venueId = 1L;
        serviceCase.status = "OPEN";
        return serviceCase;
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.GET, "/api/venues/**").permitAll()
                    .anyRequest().authenticated())
                .build();
        }
    }
}
