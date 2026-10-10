package com.example.venue.system;

import com.example.venue.common.AuditLog;
import com.example.venue.common.SensitiveDataMasker;
import com.example.venue.security.ClientIpResolver;
import com.example.venue.security.SecurityContext;
import com.example.venue.system.entity.SysOperationLog;
import com.example.venue.system.mapper.SysOperationLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

@Aspect
@Component
public class AuditLogAspect {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuditLogAspect.class);
    private static final int ERROR_MESSAGE_MAX_LENGTH = 512;
    private static final int PARAMS_MAX_LENGTH = 60000;
    private static final String TRUNCATED_SUFFIX = "...[truncated]";
    // 终态更新有界重试上限。偶发写入失败经重试自愈；持续失败不再静默吞掉，记 WARN 供运维定位残留 PENDING。
    private static final int TERMINAL_UPDATE_MAX_ATTEMPTS = 3;

    private final SysOperationLogMapper operationLogMapper;
    private final ObjectMapper objectMapper;
    private final ClientIpResolver clientIpResolver;

    public AuditLogAspect(SysOperationLogMapper operationLogMapper, ObjectMapper objectMapper,
                          ClientIpResolver clientIpResolver) {
        this.operationLogMapper = operationLogMapper;
        this.objectMapper = objectMapper;
        this.clientIpResolver = clientIpResolver;
    }

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        SysOperationLog log = baseLog(auditLog);
        log.params = truncate(SensitiveDataMasker.mask(objectMapper.writeValueAsString(joinPoint.getArgs())), PARAMS_MAX_LENGTH);
        log.resultStatus = "PENDING";
        // Persist the fact before the protected operation. A failed audit insert must block the operation.
        operationLogMapper.insert(log);
        try {
            Object result = joinPoint.proceed();
            log.resultStatus = "SUCCESS";
            return result;
        } catch (Throwable ex) {
            log.resultStatus = "FAIL";
            log.errorMessage = truncate(SensitiveDataMasker.mask(ex.getMessage()), ERROR_MESSAGE_MAX_LENGTH);
            throw ex;
        } finally {
            // 终态更新失败不替换业务异常、不向调用方抛出：前置审计事实已落库，这里只做有界重试+可观测 WARN。
            persistTerminalStatus(log);
        }
    }

    private void persistTerminalStatus(SysOperationLog log) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= TERMINAL_UPDATE_MAX_ATTEMPTS; attempt++) {
            try {
                operationLogMapper.updateById(log);
                return;
            } catch (RuntimeException ex) {
                lastError = ex;
            }
        }
        LOGGER.warn(
                "审计终态更新失败（已重试 {} 次），记录可能残留 PENDING：logId={}, 目标状态={}, 异常={}",
                TERMINAL_UPDATE_MAX_ATTEMPTS,
                log.id,
                log.resultStatus,
                lastError == null ? "unknown" : lastError.toString());
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - TRUNCATED_SUFFIX.length())) + TRUNCATED_SUFFIX;
    }

    private SysOperationLog baseLog(AuditLog auditLog) {
        SysOperationLog log = new SysOperationLog();
        var user = SecurityContext.currentUser();
        if (user != null) {
            log.userId = user.id();
            log.username = user.username();
        }
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attrs == null ? null : attrs.getRequest();
        log.module = auditLog.module();
        log.action = auditLog.action();
        log.method = request == null ? null : request.getMethod();
        log.requestUri = request == null ? null : request.getRequestURI();
        // 复用 RateLimitFilter 的可信代理 IP 解析，避免操作日志全部记成反向代理内网地址（A26-P2-26）。
        log.ip = request == null ? null : clientIpResolver.resolveClientIp(request);
        log.createTime = LocalDateTime.now();
        return log;
    }
}
