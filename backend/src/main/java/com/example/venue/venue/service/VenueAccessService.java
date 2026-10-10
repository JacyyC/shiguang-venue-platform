package com.example.venue.venue.service;

import com.example.venue.common.BusinessException;
import com.example.venue.common.Constants;
import com.example.venue.security.SecurityContext;
import com.example.venue.venue.mapper.VenueAdminRelationMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class VenueAccessService {
    private final VenueAdminRelationMapper relationMapper;

    public VenueAccessService(VenueAdminRelationMapper relationMapper) {
        this.relationMapper = relationMapper;
    }

    public void requireVenueManageAccess(Long venueId) {
        var user = SecurityContext.currentUser();
        if (user == null) {
            throw BusinessException.forbidden("未登录");
        }
        if (user.hasRole(Constants.ROLE_SYSTEM_ADMIN)) {
            return;
        }
        if (user.hasRole(Constants.ROLE_VENUE_ADMIN) && relationMapper.countRelation(user.id(), venueId) > 0) {
            return;
        }
        throw BusinessException.forbidden("无权管理该场馆数据");
    }

    public List<Long> manageableVenueIds() {
        var user = SecurityContext.currentUser();
        if (user == null) {
            return List.of();
        }
        if (user.hasRole(Constants.ROLE_SYSTEM_ADMIN)) {
            return null;
        }
        return relationMapper.findVenueIdsByUserId(user.id());
    }

    public List<Long> managerUserIds(Long venueId) {
        return relationMapper.findUserIdsByVenueId(venueId);
    }
}
