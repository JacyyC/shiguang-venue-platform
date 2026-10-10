package com.example.venue.community.activity.service;

import com.example.venue.community.activity.entity.CommunityActivity;
import com.example.venue.community.activity.entity.CommunityCompanionRequest;
import com.example.venue.community.activity.mapper.CommunityActivityMapper;
import com.example.venue.community.activity.mapper.CommunityCompanionRequestMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class ActivityReservationReconciliationService {
    private static final int BATCH_SIZE = 50;
    private final CommunityActivityMapper activityMapper;
    private final CommunityCompanionRequestMapper companionMapper;
    private final ActivityReservationPropagationService propagationService;

    public ActivityReservationReconciliationService(CommunityActivityMapper activityMapper,
                                                    CommunityCompanionRequestMapper companionMapper,
                                                    ActivityReservationPropagationService propagationService) {
        this.activityMapper = activityMapper;
        this.companionMapper = companionMapper;
        this.propagationService = propagationService;
    }

    public int reconcile() {
        Set<Long> reservationIds = new LinkedHashSet<>();
        long afterId = 0L;
        while (true) {
            var ids = activityMapper.findConfirmedIds(afterId, BATCH_SIZE);
            if (ids.isEmpty()) {
                break;
            }
            for (Long id : ids) {
                CommunityActivity activity = activityMapper.selectById(id);
                if (activity != null && activity.reservationId != null) {
                    reservationIds.add(activity.reservationId);
                }
            }
            afterId = ids.get(ids.size() - 1);
        }
        afterId = 0L;
        while (true) {
            var ids = companionMapper.findConfirmedIds(afterId, BATCH_SIZE);
            if (ids.isEmpty()) {
                break;
            }
            for (Long id : ids) {
                CommunityCompanionRequest request = companionMapper.selectById(id);
                if (request != null && request.reservationId != null) {
                    reservationIds.add(request.reservationId);
                }
            }
            afterId = ids.get(ids.size() - 1);
        }
        reservationIds.forEach(id -> propagationService.propagate(id, "RECONCILIATION"));
        return reservationIds.size();
    }
}
