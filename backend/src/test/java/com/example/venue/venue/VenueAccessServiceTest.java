package com.example.venue.venue;

import com.example.venue.common.Constants;
import com.example.venue.common.CurrentUser;
import com.example.venue.venue.mapper.VenueAdminRelationMapper;
import com.example.venue.venue.service.VenueAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VenueAccessServiceTest {
    @Mock
    private VenueAdminRelationMapper relationMapper;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void newVenueAdminRelationImmediatelyGrantsTheScopedVenueAccess() {
        CurrentUser venueAdmin = new CurrentUser(7L, "venue-admin", Set.of(Constants.ROLE_VENUE_ADMIN), Set.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(venueAdmin, null, List.of()));
        when(relationMapper.countRelation(7L, 12L)).thenReturn(1);

        assertThatCode(() -> new VenueAccessService(relationMapper).requireVenueManageAccess(12L))
            .doesNotThrowAnyException();

        verify(relationMapper).countRelation(7L, 12L);
    }
}
