package com.example.venue.community;

import com.example.venue.community.controller.CommunityController;
import com.example.venue.common.BusinessException;
import com.example.venue.common.ResultCode;
import com.example.venue.community.dto.CommunityQueryRows;
import com.example.venue.community.dto.CommunityRequests;
import com.example.venue.community.dto.CursorPage;
import com.example.venue.community.entity.CommunityComment;
import com.example.venue.community.entity.CommunityGroup;
import com.example.venue.community.entity.CommunityGroupMember;
import com.example.venue.community.entity.CommunityPost;
import com.example.venue.community.service.CommunityContentService;
import com.example.venue.community.service.CommunityEchoService;
import com.example.venue.community.service.CommunityGroupService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import jakarta.servlet.ServletException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CommunityController.class)
@ContextConfiguration(classes = {
    CommunityController.class,
    CommunityControllerTest.MethodSecurityConfig.class
})
class CommunityControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CommunityGroupService groupService;
    @MockBean
    private CommunityContentService contentService;
    /** 回声接口是首页附属可选内容，装配必须与真实三参数构造函数一致。 */
    @MockBean
    private CommunityEchoService echoService;

    @Test
    void everyCommunityEndpointUsesTheSingleExplicitCommunityAccessAuthority() {
        PreAuthorize authorization = CommunityController.class.getAnnotation(PreAuthorize.class);
        assertThat(authorization).isNotNull();
        assertThat(authorization.value()).isEqualTo("hasAuthority('community:access')");
        assertThat(CommunityController.class.getDeclaredMethods())
            .filteredOn(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
            .allSatisfy(method -> assertThat(method.getAnnotation(PreAuthorize.class)).isNull());
    }

    @Test
    void anonymousAndVenueAdministratorOnlyUsersCannotEnterCommunityApis() throws Exception {
        mockMvc.perform(get("/api/community/groups"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/community/groups")
                .with(user("venue-admin").roles("VENUE_ADMIN")))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/community/posts")
                .with(user("venue-admin").roles("VENUE_ADMIN"))
                .header("Idempotency-Key", "venue-post")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupId\":1,\"title\":\"越权\",\"content\":\"不应进入服务\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void authorizedUserCanSearchAndCreateThroughRealRequestContracts() throws Exception {
        CommunityQueryRows.GroupRow group = new CommunityQueryRows.GroupRow();
        group.id = 1L;
        group.groupNo = "CG-1";
        group.name = "学习小组";
        group.ownerUserId = 8L;
        group.ownerNickname = "小组长";
        group.visibility = "PLATFORM_VISIBLE";
        group.joinPolicy = "DIRECT";
        group.status = "ACTIVE";
        when(groupService.search(any(CommunityRequests.GroupQuery.class)))
            .thenReturn(CursorPage.of(List.of(group), null, false));

        CommunityPost post = new CommunityPost();
        post.id = 21L;
        post.postNo = "CP-21";
        post.groupId = 1L;
        post.authorUserId = 7L;
        post.title = "周末学习";
        post.contentText = "一起复习";
        post.status = "PUBLISHED";
        post.versionNo = 0L;
        when(contentService.createPost(any(CommunityRequests.CreatePost.class), eq("post-key")))
            .thenReturn(post);

        mockMvc.perform(get("/api/community/groups")
                .with(communityUser())
                .param("namePrefix", "学习")
                .param("limit", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.records[0].id").value(1))
            .andExpect(jsonPath("$.data.records[0].owner.nickname").value("小组长"))
            .andExpect(jsonPath("$.data.records[0].owner.email").doesNotExist());

        mockMvc.perform(post("/api/community/posts")
                .with(communityUser())
                .header("Idempotency-Key", "post-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupId\":1,\"title\":\"周末学习\",\"content\":\"一起复习\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(21))
            .andExpect(jsonPath("$.data.title").value("周末学习"));

        verify(groupService).search(any(CommunityRequests.GroupQuery.class));
        verify(contentService).createPost(any(CommunityRequests.CreatePost.class), eq("post-key"));
    }

    @Test
    void searchQueryParametersAreBoundAndForwardedToServices() throws Exception {
        when(groupService.search(any(CommunityRequests.GroupQuery.class)))
            .thenReturn(CursorPage.of(List.of(), null, false));
        when(contentService.searchPosts(any(CommunityRequests.PostQuery.class)))
            .thenReturn(CursorPage.of(List.of(), null, false));

        mockMvc.perform(get("/api/community/groups")
                .with(communityUser())
                .param("namePrefix", "学习")
                .param("cursorId", "91")
                .param("limit", "13"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/community/posts")
                .with(communityUser())
                .param("groupId", "11")
                .param("authorId", "12")
                .param("titlePrefix", "周末")
                .param("cursorId", "92")
                .param("limit", "14"))
            .andExpect(status().isOk());

        ArgumentCaptor<CommunityRequests.GroupQuery> groupQueryCaptor =
            ArgumentCaptor.forClass(CommunityRequests.GroupQuery.class);
        verify(groupService).search(groupQueryCaptor.capture());
        CommunityRequests.GroupQuery groupQuery = groupQueryCaptor.getValue();
        assertThat(groupQuery.namePrefix).isEqualTo("学习");
        assertThat(groupQuery.cursorId).isEqualTo(91L);
        assertThat(groupQuery.limit).isEqualTo(13);

        ArgumentCaptor<CommunityRequests.PostQuery> postQueryCaptor =
            ArgumentCaptor.forClass(CommunityRequests.PostQuery.class);
        verify(contentService).searchPosts(postQueryCaptor.capture());
        CommunityRequests.PostQuery postQuery = postQueryCaptor.getValue();
        assertThat(postQuery.groupId).isEqualTo(11L);
        assertThat(postQuery.authorId).isEqualTo(12L);
        assertThat(postQuery.titlePrefix).isEqualTo("周末");
        assertThat(postQuery.cursorId).isEqualTo(92L);
        assertThat(postQuery.limit).isEqualTo(14);
    }

    @Test
    void groupQueriesForwardMineAndCategoryAndCreateRequiresCategory() throws Exception {
        when(groupService.search(any(CommunityRequests.GroupQuery.class)))
            .thenReturn(CursorPage.of(List.of(), null, false));

        mockMvc.perform(get("/api/community/groups")
                .with(communityUser())
                .param("mine", "true")
                .param("category", "OTHER"))
            .andExpect(status().isOk());

        ArgumentCaptor<CommunityRequests.GroupQuery> groupQueryCaptor =
            ArgumentCaptor.forClass(CommunityRequests.GroupQuery.class);
        verify(groupService).search(groupQueryCaptor.capture());
        assertThat(groupQueryCaptor.getValue().mine).isTrue();
        assertThat(groupQueryCaptor.getValue().category).isEqualTo("OTHER");

        mockMvc.perform(post("/api/community/groups")
                .with(communityUser())
                .header("Idempotency-Key", "group-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"晨读会\",\"visibility\":\"PLATFORM_VISIBLE\",\"joinPolicy\":\"DIRECT\"}"))
            .andExpect(status().isBadRequest());

        verify(groupService, never()).create(any(CommunityRequests.CreateGroup.class), any());
    }

    @Test
    void groupDetailReturnsCurrentMembershipAndPublicOwnerSummary() throws Exception {
        CommunityQueryRows.GroupRow group = new CommunityQueryRows.GroupRow();
        group.id = 1L;
        group.groupNo = "CG-1";
        group.name = "学习小组";
        group.ownerUserId = 8L;
        group.ownerNickname = "小组长";
        group.visibility = "PLATFORM_VISIBLE";
        group.joinPolicy = "REVIEW_REQUIRED";
        group.status = "ACTIVE";
        group.currentMemberRole = "MEMBER";
        group.currentMemberStatus = "PENDING";
        group.memberCount = 12L;
        group.versionNo = 0L;
        when(groupService.get(1L)).thenReturn(group);

        mockMvc.perform(get("/api/community/groups/1").with(communityUser()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.currentMemberRole").value("MEMBER"))
            .andExpect(jsonPath("$.data.currentMemberStatus").value("PENDING"))
            .andExpect(jsonPath("$.data.memberCount").value(12))
            .andExpect(jsonPath("$.data.owner.nickname").value("小组长"))
            .andExpect(jsonPath("$.data.owner.email").doesNotExist())
            .andExpect(jsonPath("$.data.owner.phone").doesNotExist());
    }

    @Test
    void missingGroupDetailKeepsTheNonEnumeratingNotFoundContract() throws Exception {
        when(groupService.get(2L)).thenThrow(BusinessException.notFound("社区资源不存在"));

        assertThatThrownBy(() -> mockMvc.perform(get("/api/community/groups/2").with(communityUser())))
            .isInstanceOf(ServletException.class)
            .hasCauseInstanceOf(BusinessException.class)
            .satisfies(error -> {
                BusinessException cause = (BusinessException) error.getCause();
                assertThat(cause.getCode()).isEqualTo(ResultCode.NOT_FOUND);
                assertThat(cause.getMessage()).isEqualTo("社区资源不存在");
            });
    }

    @Test
    void memberRoleChangesRequireAndForwardTheIdempotencyKey() throws Exception {
        CommunityGroupMember member = new CommunityGroupMember();
        member.id = 109L;
        member.groupId = 1L;
        member.userId = 9L;
        member.memberRole = "ADMIN";
        member.status = "ACTIVE";
        when(groupService.changeRole(eq(1L), eq(109L),
            any(CommunityRequests.ChangeMemberRole.class), eq("role-change-key"))).thenReturn(member);

        mockMvc.perform(put("/api/community/groups/1/members/109/role")
                .with(communityUser())
                .header("Idempotency-Key", "role-change-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(109))
            .andExpect(jsonPath("$.data.role").value("ADMIN"));

        mockMvc.perform(put("/api/community/groups/1/members/109/role")
                .with(communityUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\"}"))
            .andExpect(status().isBadRequest());

        verify(groupService).changeRole(eq(1L), eq(109L),
            any(CommunityRequests.ChangeMemberRole.class), eq("role-change-key"));
    }

    @Test
    void updateEndpointsRequireAndForwardTheIdempotencyKey() throws Exception {
        CommunityGroup group = new CommunityGroup();
        group.id = 1L;
        group.name = "新组名";
        group.visibility = "PLATFORM_VISIBLE";
        group.joinPolicy = "DIRECT";
        group.status = "ACTIVE";
        group.versionNo = 1L;
        when(groupService.update(eq(1L), any(CommunityRequests.UpdateGroup.class), eq("group-update")))
            .thenReturn(group);

        CommunityPost post = new CommunityPost();
        post.id = 21L;
        post.groupId = 1L;
        post.authorUserId = 7L;
        post.title = "新标题";
        post.contentText = "新正文";
        post.status = "PUBLISHED";
        post.versionNo = 1L;
        when(contentService.updatePost(eq(21L), any(CommunityRequests.UpdatePost.class), eq("post-update")))
            .thenReturn(post);

        CommunityComment comment = new CommunityComment();
        comment.id = 31L;
        comment.postId = 21L;
        comment.groupId = 1L;
        comment.authorUserId = 7L;
        comment.contentText = "新评论";
        comment.status = "PUBLISHED";
        comment.versionNo = 1L;
        when(contentService.updateComment(
            eq(31L), any(CommunityRequests.UpdateComment.class), eq("comment-update")))
            .thenReturn(comment);

        mockMvc.perform(put("/api/community/groups/1")
                .with(communityUser())
                .header("Idempotency-Key", "group-update")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"新组名\",\"description\":\"说明\","
                    + "\"visibility\":\"PLATFORM_VISIBLE\",\"joinPolicy\":\"DIRECT\","
                    + "\"category\":\"STUDY\",\"versionNo\":0}"))
            .andExpect(status().isOk());
        mockMvc.perform(put("/api/community/posts/21")
                .with(communityUser())
                .header("Idempotency-Key", "post-update")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"新标题\",\"content\":\"新正文\",\"versionNo\":0}"))
            .andExpect(status().isOk());
        mockMvc.perform(put("/api/community/comments/31")
                .with(communityUser())
                .header("Idempotency-Key", "comment-update")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"新评论\",\"versionNo\":0}"))
            .andExpect(status().isOk());

        mockMvc.perform(put("/api/community/groups/1")
                .with(communityUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"新组名\",\"visibility\":\"PLATFORM_VISIBLE\","
                    + "\"joinPolicy\":\"DIRECT\",\"versionNo\":0}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/community/posts/21")
                .with(communityUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"新标题\",\"content\":\"新正文\",\"versionNo\":0}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/community/comments/31")
                .with(communityUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"新评论\",\"versionNo\":0}"))
            .andExpect(status().isBadRequest());

        verify(groupService).update(eq(1L), any(CommunityRequests.UpdateGroup.class), eq("group-update"));
        verify(contentService).updatePost(eq(21L), any(CommunityRequests.UpdatePost.class), eq("post-update"));
        verify(contentService).updateComment(
            eq(31L), any(CommunityRequests.UpdateComment.class), eq("comment-update"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor communityUser() {
        return user("community-user")
            .authorities(new SimpleGrantedAuthority("community:access"));
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
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .build();
        }
    }
}
