package com.example.venue.notice.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.common.PageResult;
import com.example.venue.notice.dto.NoticeDeliveryQuery;
import com.example.venue.notice.dto.NoticeExperimentResponse;
import com.example.venue.notice.dto.SaveNoticeExperimentRequest;
import com.example.venue.notice.entity.NoticeExperiment;
import com.example.venue.notice.entity.NoticeExperimentVariant;
import com.example.venue.notice.mapper.NoticeExperimentMapper;
import com.example.venue.notice.mapper.NoticeExperimentVariantMapper;
import com.example.venue.notice.provider.NotificationProviderRouter;
import com.example.venue.security.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class NoticeExperimentService {
    private static final DateTimeFormatter EXPERIMENT_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final NoticeExperimentMapper experimentMapper;
    private final NoticeExperimentVariantMapper variantMapper;
    private final NotificationProviderRouter providerRouter;

    public NoticeExperimentService(NoticeExperimentMapper experimentMapper,
                                   NoticeExperimentVariantMapper variantMapper,
                                   NotificationProviderRouter providerRouter) {
        this.experimentMapper = experimentMapper;
        this.variantMapper = variantMapper;
        this.providerRouter = providerRouter;
    }

    @Transactional
    public NoticeExperimentResponse create(SaveNoticeExperimentRequest request) {
        ValidExperiment valid = validate(request);
        CurrentUser user = SecurityContext.currentUser();
        if (user == null) {
            throw BusinessException.forbidden("未登录");
        }

        LocalDateTime now = LocalDateTime.now();
        NoticeExperiment experiment = new NoticeExperiment();
        experiment.experimentNo = nextExperimentNo(now);
        experiment.name = valid.name();
        experiment.status = "RUNNING";
        experiment.targetMetric = valid.targetMetric();
        experiment.sampleScope = valid.sampleScope();
        experiment.startTime = now;
        experiment.ownerId = user.id();
        experiment.createTime = now;
        experiment.updateTime = now;
        experiment.deleted = 0;
        experimentMapper.insert(experiment);

        NoticeExperimentResponse response = toResponse(experiment);
        for (SaveNoticeExperimentRequest.Variant requestVariant : valid.variants()) {
            NoticeExperimentVariant variant = new NoticeExperimentVariant();
            variant.experimentId = experiment.id;
            variant.variantCode = normalizeRequired(requestVariant.variantCode, "实验版本编码不能为空")
                .toUpperCase(Locale.ROOT);
            variant.title = normalizeRequired(requestVariant.title, "实验版本标题不能为空");
            variant.content = normalizeRequired(requestVariant.content, "实验版本内容不能为空");
            variant.allocationPercent = requestVariant.allocationPercent;
            variant.createTime = now;
            variantMapper.insert(variant);
            response.variants.add(toResponse(variant));
        }
        return response;
    }

    public NoticeExperimentResponse toResponse(NoticeExperiment experiment) {
        NoticeExperimentResponse response = new NoticeExperimentResponse();
        response.id = experiment.id;
        response.experimentNo = experiment.experimentNo;
        response.name = experiment.name;
        response.status = experiment.status;
        response.targetMetric = experiment.targetMetric;
        response.sampleScope = experiment.sampleScope;
        response.startTime = experiment.startTime;
        response.endTime = experiment.endTime;
        response.ownerId = experiment.ownerId;
        return response;
    }

    public PageResult<NoticeExperimentResponse> experiments(NoticeDeliveryQuery query) {
        NoticeDeliveryQuery safeQuery = query == null ? new NoticeDeliveryQuery() : query;
        requireProviderContext(safeQuery);
        QueryWrapper<NoticeExperiment> wrapper = new QueryWrapper<NoticeExperiment>()
            .eq("deleted", 0);
        if (safeQuery.experimentId != null) {
            wrapper.eq("id", safeQuery.experimentId);
        }
        if (safeQuery.normalizedStatus() != null) {
            wrapper.eq("status", safeQuery.normalizedStatus());
        }
        if (safeQuery.startTime != null) {
            wrapper.ge("create_time", safeQuery.startTime);
        }
        if (safeQuery.endTime != null) {
            wrapper.lt("create_time", safeQuery.endTime);
        }
        Page<NoticeExperiment> page = experimentMapper.selectPage(
            new Page<>(safeQuery.getPageNum(), safeQuery.getPageSize()),
            wrapper.orderByDesc("id"));
        List<NoticeExperimentResponse> records = page.getRecords().stream()
            .map(this::toResponseWithVariants)
            .toList();
        return PageResult.of(records, page.getTotal(), safeQuery.getPageNum(), safeQuery.getPageSize());
    }

    private void requireProviderContext(NoticeDeliveryQuery query) {
        String providerEnv = query.providerEnv();
        String providerName = query.providerName();
        if (providerEnv == null && providerName == null) {
            providerRouter.require(NoticeDeliveryService.PROVIDER_ENV, NoticeDeliveryService.PROVIDER_NAME);
            return;
        }
        providerRouter.require(providerEnv, providerName);
    }

    private NoticeExperimentResponse.Variant toResponse(NoticeExperimentVariant variant) {
        NoticeExperimentResponse.Variant response = new NoticeExperimentResponse.Variant();
        response.id = variant.id;
        response.experimentId = variant.experimentId;
        response.variantCode = variant.variantCode;
        response.title = variant.title;
        response.content = variant.content;
        response.allocationPercent = variant.allocationPercent;
        return response;
    }

    private NoticeExperimentResponse toResponseWithVariants(NoticeExperiment experiment) {
        NoticeExperimentResponse response = toResponse(experiment);
        if (experiment.id != null) {
            List<NoticeExperimentVariant> variants = variantMapper.findByExperimentId(experiment.id);
            if (variants != null) {
                response.variants = variants.stream().map(this::toResponse).toList();
            }
        }
        return response;
    }

    private ValidExperiment validate(SaveNoticeExperimentRequest request) {
        if (request == null) {
            throw BusinessException.badRequest("公告实验参数不能为空");
        }
        String name = normalizeRequired(request.name, "实验名称不能为空");
        String targetMetric = defaultUpper(request.targetMetric, "READ_RATE");
        String sampleScope = defaultUpper(request.sampleScope, "ALL_USERS");
        List<SaveNoticeExperimentRequest.Variant> variants = request.variants == null ? List.of() : request.variants;
        if (variants.size() < 2) {
            throw BusinessException.badRequest("公告实验至少需要两个版本");
        }

        Set<String> variantCodes = new LinkedHashSet<>();
        int allocationTotal = 0;
        for (SaveNoticeExperimentRequest.Variant variant : variants) {
            if (variant == null) {
                throw BusinessException.badRequest("实验版本不能为空");
            }
            String code = normalizeRequired(variant.variantCode, "实验版本编码不能为空").toUpperCase(Locale.ROOT);
            if (!variantCodes.add(code)) {
                throw BusinessException.badRequest("实验版本编码不能重复");
            }
            normalizeRequired(variant.title, "实验版本标题不能为空");
            normalizeRequired(variant.content, "实验版本内容不能为空");
            if (variant.allocationPercent == null) {
                throw BusinessException.badRequest("实验版本流量占比不能为空");
            }
            if (variant.allocationPercent < 0 || variant.allocationPercent > 100) {
                throw BusinessException.badRequest("实验版本流量占比必须在 0 到 100 之间");
            }
            allocationTotal += variant.allocationPercent;
        }
        if (allocationTotal != 100) {
            throw BusinessException.badRequest("实验版本流量占比合计必须为 100");
        }
        return new ValidExperiment(name, targetMetric, sampleScope, variants);
    }

    private String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw BusinessException.badRequest(message);
        }
        return value.trim();
    }

    private String defaultUpper(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String nextExperimentNo(LocalDateTime now) {
        return "NE" + now.format(EXPERIMENT_NO_TIME)
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private record ValidExperiment(String name, String targetMetric, String sampleScope,
                                   List<SaveNoticeExperimentRequest.Variant> variants) {
    }
}
