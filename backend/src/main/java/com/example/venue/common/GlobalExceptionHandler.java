package com.example.venue.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import com.example.venue.resource.exception.ResourceAllocationConflictException;
import com.example.venue.reservation.exception.ReservationSubmissionStateException;

import java.io.IOException;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(statusOf(ex.getCode())).body(Result.fail(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(ResourceAllocationConflictException.class)
    public ResponseEntity<Result<Map<String, String>>> handleResourceAllocationConflict(
        ResourceAllocationConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Result.fail(
            ResultCode.CONFLICT,
            ex.getMessage(),
            Map.of("reasonCode", ex.getConflictCode().name())));
    }

    /**
     * 预约提交请求身份的冲突（同键仍在处理中 / 同键不同载荷）。
     * 与库存冲突同为 409，但携带独立的 reasonCode，前端必须分开恢复，不能回落为时段被占。
     */
    @ExceptionHandler(ReservationSubmissionStateException.class)
    public ResponseEntity<Result<Map<String, String>>> handleReservationSubmissionState(
        ReservationSubmissionStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Result.fail(
            ResultCode.CONFLICT,
            ex.getMessage(),
            Map.of("reasonCode", ex.getState().name())));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class, ConstraintViolationException.class})
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Result<Map<String, String>> handleValidation(Exception ex) {
        Map<String, String> errors = validationErrors(ex);
        String message = errors.isEmpty()
            ? "请求参数不合法"
            : "请求参数不合法: " + errors.entrySet().stream()
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .collect(Collectors.joining("; "));
        return Result.fail(ResultCode.VALIDATION_ERROR, message, errors);
    }

    @ExceptionHandler(DateTimeParseException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Result<Map<String, String>> handleDateTimeParse(DateTimeParseException ex) {
        return Result.fail(ResultCode.VALIDATION_ERROR, "请求参数不合法: date 日期格式不合法", Map.of("date", "日期格式不合法"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleUnreadableRequest(HttpMessageNotReadableException ex) {
        return Result.fail(ResultCode.BAD_REQUEST, "请求体不能为空或格式错误");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingRequestParameter(MissingServletRequestParameterException ex) {
        String message = "缺少必填请求参数: " + ex.getParameterName();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.fail(ResultCode.BAD_REQUEST, message));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Result<Void>> handleMissingRequestHeader(MissingRequestHeaderException ex) {
        String message = "缺少必填请求头: " + ex.getHeaderName();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.fail(ResultCode.BAD_REQUEST, message));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = "请求参数类型不合法: " + ex.getName();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.fail(ResultCode.BAD_REQUEST, message));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.fail(ResultCode.NOT_FOUND, "资源不存在"));
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Void> handleAsyncRequestTimeout(AsyncRequestTimeoutException ex) {
        log.debug("Async request timed out before the response could be written");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    // A26-P3-39：补 405/415/413 客户端错误处理器。原 @ExceptionHandler(Exception.class) fallback 会把
    // 这些异常归类为 500 并打 ERROR 栈，未认证攻击者可据此廉价刷爆错误日志。改用 log.debug 记录客户端错误
    // 摘要并返回正确状态码，与现有 handleNoResource(404)、handleMissingRequestParameter(400) 风格一致。
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.debug("Method not supported: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(Result.fail(ResultCode.BAD_REQUEST, "请求方法不被支持"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.debug("Media type not supported: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .body(Result.fail(ResultCode.BAD_REQUEST, "请求内容类型不被支持"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        log.debug("Max upload size exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(Result.fail(ResultCode.BAD_REQUEST, "上传文件大小超过限制"));
    }

    @ExceptionHandler({AccessDeniedException.class})
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleForbidden(Exception ex) {
        return Result.fail(ResultCode.FORBIDDEN, "无权限访问该资源");
    }

    @ExceptionHandler({BadCredentialsException.class})
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleAuth(Exception ex) {
        return Result.fail(ResultCode.UNAUTHORIZED, "用户名或密码错误");
    }

    @ExceptionHandler(DisabledException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleDisabled(DisabledException ex) {
        return Result.fail(ResultCode.UNAUTHORIZED, "账号已被禁用");
    }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleAuthentication(Exception ex) {
        return Result.fail(ResultCode.UNAUTHORIZED, "认证失败");
    }

    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> handleDuplicate(DuplicateKeyException ex) {
        return Result.fail(ResultCode.CONFLICT, "数据已存在或预约时段已被占用");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleDataIntegrity(DataIntegrityViolationException ex) {
        return Result.fail(ResultCode.BAD_REQUEST, "请求参数不合法");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnknown(Exception ex) {
        if (isClientDisconnect(ex)) {
            log.debug("Client disconnected before the response could be written: {}", ex.getMessage());
            return null;
        }
        log.error("Unhandled error", ex);
        return Result.fail(ResultCode.SYSTEM_ERROR, "系统异常，请稍后重试");
    }

    private HttpStatus statusOf(ResultCode code) {
        return switch (code) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case VALIDATION_ERROR -> HttpStatus.UNPROCESSABLE_ENTITY;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case SYSTEM_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private Map<String, String> validationErrors(Exception ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (ex instanceof BindException bindException) {
            for (FieldError error : bindException.getBindingResult().getFieldErrors()) {
                errors.putIfAbsent(error.getField(), error.getDefaultMessage());
            }
            return errors;
        }
        if (ex instanceof ConstraintViolationException violationException) {
            violationException.getConstraintViolations().forEach(violation -> {
                String path = violation.getPropertyPath() == null ? "parameter" : violation.getPropertyPath().toString();
                int dotIndex = path.lastIndexOf('.');
                String field = dotIndex >= 0 ? path.substring(dotIndex + 1) : path;
                errors.putIfAbsent(field, violation.getMessage());
            });
        }
        return errors;
    }

    private boolean isClientDisconnect(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof AsyncRequestNotUsableException) {
                return true;
            }
            if (current instanceof IOException) {
                String message = current.getMessage();
                if (message == null) {
                    continue;
                }
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("broken pipe")
                    || normalized.contains("connection reset")
                    || normalized.contains("connection aborted")) {
                    return true;
                }
            }
        }
        return false;
    }
}
