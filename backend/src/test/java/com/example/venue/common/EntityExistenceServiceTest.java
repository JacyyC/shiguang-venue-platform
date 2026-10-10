package com.example.venue.common;

import com.example.venue.user.entity.SysUser;
import com.example.venue.user.mapper.SysUserMapper;
import com.example.venue.venue.entity.Venue;
import com.example.venue.venue.entity.VenueSpace;
import com.example.venue.venue.mapper.VenueMapper;
import com.example.venue.venue.mapper.VenueSpaceMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntityExistenceServiceTest {
    @Mock
    private SysUserMapper userMapper;
    @Mock
    private VenueMapper venueMapper;
    @Mock
    private VenueSpaceMapper spaceMapper;
    @InjectMocks
    private EntityExistenceService service;

    // ---- requireActiveUser ----

    @Test
    void requireActiveUserRejectsNullId() {
        assertThatThrownBy(() -> service.requireActiveUser(null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("用户ID不能为空");
    }

    @Test
    void requireActiveUserRejectsMissingUser() {
        when(userMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.requireActiveUser(99L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("用户不存在");
    }

    @Test
    void requireActiveUserRejectsLogicallyDeletedUser() {
        SysUser user = new SysUser();
        user.id = 10L;
        user.deleted = 1;
        user.status = Constants.ACTIVE;
        when(userMapper.selectById(10L)).thenReturn(user);
        assertThatThrownBy(() -> service.requireActiveUser(10L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("用户不存在");
    }

    @Test
    void requireActiveUserRejectsDisabledUser() {
        SysUser user = new SysUser();
        user.id = 11L;
        user.deleted = 0;
        user.status = Constants.DISABLED;
        when(userMapper.selectById(11L)).thenReturn(user);
        assertThatThrownBy(() -> service.requireActiveUser(11L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("用户已禁用");
    }

    @Test
    void requireActiveUserPassesForActiveUser() {
        SysUser user = new SysUser();
        user.id = 12L;
        user.deleted = 0;
        user.status = Constants.ACTIVE;
        when(userMapper.selectById(12L)).thenReturn(user);
        assertThatCode(() -> service.requireActiveUser(12L)).doesNotThrowAnyException();
    }

    @Test
    void requireActiveUserPassesWhenDeletedFieldIsNull() {
        // 历史数据可能 deleted 为 null，应视为未删除放行
        SysUser user = new SysUser();
        user.id = 13L;
        user.deleted = null;
        user.status = Constants.ACTIVE;
        when(userMapper.selectById(13L)).thenReturn(user);
        assertThatCode(() -> service.requireActiveUser(13L)).doesNotThrowAnyException();
    }

    // ---- requireVenueExists ----

    @Test
    void requireVenueExistsRejectsNullId() {
        assertThatThrownBy(() -> service.requireVenueExists(null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("场馆ID不能为空");
    }

    @Test
    void requireVenueExistsRejectsMissingVenue() {
        when(venueMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.requireVenueExists(99L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("场馆不存在");
    }

    @Test
    void requireVenueExistsRejectsLogicallyDeletedVenue() {
        Venue venue = new Venue();
        venue.id = 20L;
        venue.deleted = 1;
        when(venueMapper.selectById(20L)).thenReturn(venue);
        assertThatThrownBy(() -> service.requireVenueExists(20L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("场馆不存在");
    }

    @Test
    void requireVenueExistsPassesForExistingVenue() {
        Venue venue = new Venue();
        venue.id = 21L;
        venue.deleted = 0;
        when(venueMapper.selectById(21L)).thenReturn(venue);
        assertThatCode(() -> service.requireVenueExists(21L)).doesNotThrowAnyException();
    }

    @Test
    void requireVenueExistsPassesWhenDeletedFieldIsNull() {
        Venue venue = new Venue();
        venue.id = 22L;
        venue.deleted = null;
        when(venueMapper.selectById(22L)).thenReturn(venue);
        assertThatCode(() -> service.requireVenueExists(22L)).doesNotThrowAnyException();
    }

    // ---- requireVenueForUpdate（DC-03 收口）----

    @Test
    void requireVenueForUpdateRejectsNullId() {
        assertThatThrownBy(() -> service.requireVenueForUpdate(null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("场馆ID不能为空");
    }

    @Test
    void requireVenueForUpdateRejectsMissingVenue() {
        when(venueMapper.selectByIdForUpdate(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.requireVenueForUpdate(99L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("场馆不存在");
    }

    @Test
    void requireVenueForUpdateRejectsLogicallyDeletedVenue() {
        Venue venue = new Venue();
        venue.id = 30L;
        venue.deleted = 1;
        when(venueMapper.selectByIdForUpdate(30L)).thenReturn(venue);
        assertThatThrownBy(() -> service.requireVenueForUpdate(30L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("场馆不存在");
    }

    @Test
    void requireVenueForUpdateReturnsLockedVenueForExistingVenue() {
        Venue venue = new Venue();
        venue.id = 31L;
        venue.deleted = 0;
        when(venueMapper.selectByIdForUpdate(31L)).thenReturn(venue);
        Venue locked = service.requireVenueForUpdate(31L);
        assertThat(locked).isSameAs(venue);
    }

    @Test
    void requireVenueForUpdatePassesWhenDeletedFieldIsNull() {
        // 历史数据 deleted 为 null 视为未删除放行
        Venue venue = new Venue();
        venue.id = 32L;
        venue.deleted = null;
        when(venueMapper.selectByIdForUpdate(32L)).thenReturn(venue);
        assertThatCode(() -> service.requireVenueForUpdate(32L)).doesNotThrowAnyException();
    }

    // ---- requireSpaceForUpdate（DC-03 收口）----

    @Test
    void requireSpaceForUpdateRejectsNullId() {
        assertThatThrownBy(() -> service.requireSpaceForUpdate(null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("场地ID不能为空");
    }

    @Test
    void requireSpaceForUpdateRejectsMissingSpace() {
        when(spaceMapper.selectByIdForUpdate(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.requireSpaceForUpdate(99L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("场地不存在");
    }

    @Test
    void requireSpaceForUpdateRejectsLogicallyDeletedSpace() {
        VenueSpace space = new VenueSpace();
        space.id = 40L;
        space.deleted = 1;
        when(spaceMapper.selectByIdForUpdate(40L)).thenReturn(space);
        assertThatThrownBy(() -> service.requireSpaceForUpdate(40L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("场地不存在");
    }

    @Test
    void requireSpaceForUpdateReturnsLockedSpaceForExistingSpace() {
        VenueSpace space = new VenueSpace();
        space.id = 41L;
        space.deleted = 0;
        when(spaceMapper.selectByIdForUpdate(41L)).thenReturn(space);
        VenueSpace locked = service.requireSpaceForUpdate(41L);
        assertThat(locked).isSameAs(space);
    }

    @Test
    void requireSpaceForUpdatePassesWhenDeletedFieldIsNull() {
        VenueSpace space = new VenueSpace();
        space.id = 42L;
        space.deleted = null;
        when(spaceMapper.selectByIdForUpdate(42L)).thenReturn(space);
        assertThatCode(() -> service.requireSpaceForUpdate(42L)).doesNotThrowAnyException();
    }
}
