package com.example.venue.common;

import com.example.venue.resource.enums.AllocationConflictCode;
import com.example.venue.resource.exception.ResourceAllocationConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.DisabledException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void validationResponseIncludesFieldDetails() {
        BindException exception = new BindException(new PageQuery(), "pageQuery");
        exception.rejectValue("pageSize", "Max", "最大不能超过100");

        Result<Map<String, String>> result = handler.handleValidation(exception);

        assertThat(result.getCode()).isEqualTo(ResultCode.VALIDATION_ERROR.getCode());
        assertThat(result.getMessage()).contains("pageSize");
        assertThat(result.getData()).containsEntry("pageSize", "最大不能超过100");
    }

    @Test
    void disabledAuthenticationReturnsUnauthorizedBusinessMessage() {
        Result<Void> result = handler.handleDisabled(new DisabledException("用户已被禁用"));

        assertThat(result.getCode()).isEqualTo(ResultCode.UNAUTHORIZED.getCode());
        assertThat(result.getMessage()).isEqualTo("账号已被禁用");
    }

    @Test
    void dateParseErrorReturnsValidationResult() {
        DateTimeParseException exception = null;
        try {
            LocalDate.parse("2026-99-99");
        } catch (DateTimeParseException ex) {
            exception = ex;
        }

        Result<Map<String, String>> result = handler.handleDateTimeParse(exception);

        assertThat(result.getCode()).isEqualTo(ResultCode.VALIDATION_ERROR.getCode());
        assertThat(result.getData()).containsEntry("date", "日期格式不合法");
    }

    @Test
    void missingRequestParameterReturnsBadRequest() {
        ResponseEntity<Result<Void>> response = handler.handleMissingRequestParameter(
            new MissingServletRequestParameterException("spaceId", "Long"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.BAD_REQUEST.getCode());
        assertThat(response.getBody().getMessage()).contains("spaceId");
    }

    @Test
    void typeMismatchRequestParameterReturnsBadRequest() throws Exception {
        Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("parameterTarget", Long.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        MethodArgumentTypeMismatchException exception = new MethodArgumentTypeMismatchException(
            "abc", Long.class, "venueId", parameter, new IllegalArgumentException("bad number"));

        ResponseEntity<Result<Void>> response = handler.handleTypeMismatch(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).contains("venueId");
    }

    @Test
    void noResourceReturnsNotFound() {
        ResponseEntity<Result<Void>> response = handler.handleNoResource(
            new NoResourceFoundException(HttpMethod.GET, "/missing-page"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.NOT_FOUND.getCode());
    }

    @Test
    void clientDisconnectDoesNotReturnBodyForResponseWriter() {
        Result<Void> result = handler.handleUnknown(new IOException("Broken pipe"));

        assertThat(result).isNull();
    }

    @Test
    void wrappedClientDisconnectDoesNotReturnBodyForResponseWriter() {
        Result<Void> result = handler.handleUnknown(new IllegalStateException(
            "Failed to send [event]", new AsyncRequestNotUsableException("Response not usable after response errors.")));

        assertThat(result).isNull();
    }

    @Test
    void asyncRequestTimeoutReturnsEmptyServiceUnavailableResponse() {
        ResponseEntity<Void> response = handler.handleAsyncRequestTimeout(new AsyncRequestTimeoutException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void ordinaryDataIntegrityViolationsReturnBadRequestInsteadOfSystemError() {
        Result<Void> result = handler.handleDataIntegrity(new DataIntegrityViolationException("value too long"));

        assertThat(result.getCode()).isEqualTo(ResultCode.BAD_REQUEST.getCode());
        assertThat(result.getMessage()).isEqualTo("请求参数不合法");
    }

    @Test
    void resourceAllocationConflictReturnsStableReasonCode() {
        ResponseEntity<Result<Map<String, String>>> response = handler.handleResourceAllocationConflict(
            new ResourceAllocationConflictException(AllocationConflictCode.CAPACITY_EXCEEDED, "该时段剩余容量不足"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.CONFLICT.getCode());
        assertThat(response.getBody().getData()).containsEntry("reasonCode", "CAPACITY_EXCEEDED");
    }

    // A26-P3-39：原 @ExceptionHandler(Exception.class) fallback 会把 405/415/413 客户端错误归为 500
    // 并打 ERROR 栈，未认证攻击者可据此廉价刷爆错误日志。修复后这些异常应归到正确状态码，
    // body 仍走统一 Result 协议，避免触发 log.error。
    @Test
    void methodNotSupportedReturns405InsteadOf500() {
        ResponseEntity<Result<Void>> response = handler.handleMethodNotSupported(
            new HttpRequestMethodNotSupportedException("DELETE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.BAD_REQUEST.getCode());
        assertThat(response.getBody().getMessage()).isEqualTo("请求方法不被支持");
    }

    @Test
    void mediaTypeNotSupportedReturns415InsteadOf500() {
        ResponseEntity<Result<Void>> response = handler.handleMediaTypeNotSupported(
            new HttpMediaTypeNotSupportedException(MediaType.APPLICATION_XML, List.of(MediaType.APPLICATION_JSON)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.BAD_REQUEST.getCode());
        assertThat(response.getBody().getMessage()).isEqualTo("请求内容类型不被支持");
    }

    @Test
    void maxUploadSizeExceededReturns413InsteadOf500() {
        ResponseEntity<Result<Void>> response = handler.handleMaxUploadSizeExceeded(
            new MaxUploadSizeExceededException(5L * 1024 * 1024));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.BAD_REQUEST.getCode());
        assertThat(response.getBody().getMessage()).isEqualTo("上传文件大小超过限制");
    }

    @SuppressWarnings("unused")
    private void parameterTarget(Long venueId) {
    }
}
