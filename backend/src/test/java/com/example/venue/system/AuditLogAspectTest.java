package com.example.venue.system;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.venue.common.AuditLog;
import com.example.venue.security.ClientIpResolver;
import com.example.venue.system.entity.SysOperationLog;
import com.example.venue.system.mapper.SysOperationLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogAspectTest {
    @Mock
    private SysOperationLogMapper operationLogMapper;
    @Mock
    private ProceedingJoinPoint joinPoint;
    @Mock
    private AuditLog auditLog;
    @Mock
    private ClientIpResolver clientIpResolver;

    @AfterEach
    void resetRequestContext() {
        // 防御性清理：个别测试设置 ServletRequestAttributes 后必须复位，避免污染后续测试。
        RequestContextHolder.resetRequestAttributes();
    }

    private AuditLogAspect aspect() {
        return new AuditLogAspect(operationLogMapper, new ObjectMapper(), clientIpResolver);
    }

    @Test
    void longErrorMessageIsTruncatedBeforeInsert() throws Throwable {
        AuditLogAspect aspect = aspect();
        RuntimeException original = new RuntimeException("x".repeat(900));
        when(auditLog.module()).thenReturn("预约管理");
        when(auditLog.action()).thenReturn("提交预约");
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(joinPoint.proceed()).thenThrow(original);

        assertThatThrownBy(() -> aspect.around(joinPoint, auditLog)).isSameAs(original);

        ArgumentCaptor<SysOperationLog> logCaptor = ArgumentCaptor.forClass(SysOperationLog.class);
        verify(operationLogMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().errorMessage).hasSizeLessThanOrEqualTo(512);
    }

    @Test
    void auditInsertFailureBlocksBusinessExceptionPathBeforeProceeding() throws Throwable {
        AuditLogAspect aspect = aspect();
        when(auditLog.module()).thenReturn("预约管理");
        when(auditLog.action()).thenReturn("提交预约");
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        doThrow(new RuntimeException("audit failure")).when(operationLogMapper).insert(any(SysOperationLog.class));

        assertThatThrownBy(() -> aspect.around(joinPoint, auditLog)).hasMessage("audit failure");
    }

    @Test
    void auditInsertFailureBlocksSuccessfulBusinessCall() throws Throwable {
        AuditLogAspect aspect = aspect();
        when(auditLog.module()).thenReturn("预约管理");
        when(auditLog.action()).thenReturn("提交预约");
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        doThrow(new RuntimeException("audit failure")).when(operationLogMapper).insert(any(SysOperationLog.class));

        assertThatThrownBy(() -> aspect.around(joinPoint, auditLog)).hasMessage("audit failure");
    }

    @Test
    void terminalUpdateRetriesAndSucceedsOnTransientFailure() throws Throwable {
        AuditLogAspect aspect = aspect();
        when(auditLog.module()).thenReturn("预约管理");
        when(auditLog.action()).thenReturn("提交预约");
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(joinPoint.proceed()).thenReturn("ok");
        when(operationLogMapper.insert(any(SysOperationLog.class))).thenAnswer(inv -> {
            inv.getArgument(0, SysOperationLog.class).id = 42L;
            return 1;
        });
        // 第 1 次终态更新抛瞬时异常，第 2 次成功：业务结果正常返回，重试自愈。
        when(operationLogMapper.updateById(any(SysOperationLog.class)))
                .thenThrow(new RuntimeException("transient-failure"))
                .thenReturn(1);

        Object result = aspect.around(joinPoint, auditLog);

        assertThat(result).isEqualTo("ok");
        verify(operationLogMapper, times(2)).updateById(any(SysOperationLog.class));
    }

    @Test
    void terminalUpdateRetriesExhaustAndWarnsOnPersistentFailure() throws Throwable {
        // Logback ListAppender 直连 AuditLogAspect logger，确定性捕获 WARN 事件，
        // 不受 surefire 复用 fork 中 ConsoleAppender 早绑定原始 System.out 的影响。
        Logger aspectLogger = LoggerFactory.getLogger(AuditLogAspect.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((ch.qos.logback.classic.Logger) aspectLogger).addAppender(appender);
        try {
            AuditLogAspect aspect = aspect();
            when(auditLog.module()).thenReturn("预约管理");
            when(auditLog.action()).thenReturn("提交预约");
            when(joinPoint.getArgs()).thenReturn(new Object[0]);
            when(joinPoint.proceed()).thenReturn("ok");
            when(operationLogMapper.insert(any(SysOperationLog.class))).thenAnswer(inv -> {
                inv.getArgument(0, SysOperationLog.class).id = 42L;
                return 1;
            });
            // 终态更新持续失败：重试 3 次耗尽后记 WARN，业务结果仍正常返回（不被吞掉或替换）。
            when(operationLogMapper.updateById(any(SysOperationLog.class)))
                    .thenThrow(new RuntimeException("persistent-failure"));

            Object result = aspect.around(joinPoint, auditLog);

            assertThat(result).isEqualTo("ok");
            verify(operationLogMapper, times(3)).updateById(any(SysOperationLog.class));
            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.get(0);
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            String formatted = event.getFormattedMessage();
            assertThat(formatted).contains("logId=42");
            assertThat(formatted).contains("目标状态=SUCCESS");
            assertThat(formatted).contains("persistent-failure");
        } finally {
            ((ch.qos.logback.classic.Logger) aspectLogger).detachAppender(appender);
        }
    }

    @Test
    void auditLogRecordsClientIpFromResolverInsteadOfRemoteAddr() throws Throwable {
        // A26-P2-26：标准部署下 nginx 始终在后端前面，request.getRemoteAddr() 永远是 nginx 内网地址。
        // 修复后 baseLog 必须调 clientIpResolver.resolveClientIp 取真实客户端 IP，
        // 而不是 request.getRemoteAddr()。
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/system/users/7/password");
        request.setRemoteAddr("172.18.0.2"); // nginx 内网地址
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(clientIpResolver.resolveClientIp(request)).thenReturn("203.0.113.9");
        AuditLogAspect aspect = aspect();
        when(auditLog.module()).thenReturn("用户管理");
        when(auditLog.action()).thenReturn("重置密码");
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(joinPoint.proceed()).thenReturn("ok");
        when(operationLogMapper.insert(any(SysOperationLog.class))).thenAnswer(inv -> {
            inv.getArgument(0, SysOperationLog.class).id = 99L;
            return 1;
        });

        aspect.around(joinPoint, auditLog);

        ArgumentCaptor<SysOperationLog> logCaptor = ArgumentCaptor.forClass(SysOperationLog.class);
        verify(operationLogMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().ip).isEqualTo("203.0.113.9");
        // 防御性断言：操作日志不应直接落 request.getRemoteAddr() 的反向代理地址。
        assertThat(logCaptor.getValue().ip).isNotEqualTo("172.18.0.2");
        verify(clientIpResolver).resolveClientIp(any(HttpServletRequest.class));
    }
}
