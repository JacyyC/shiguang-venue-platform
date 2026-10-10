package com.example.venue.user.validation;

import com.example.venue.system.dto.UpdateSystemUserRequest;
import com.example.venue.user.dto.RegisterRequest;
import com.example.venue.user.dto.UpdateProfileRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三个服务端手机号写入口必须共享同一可选手机号不变量：
 * 注册 API、个人资料 API、系统用户编辑 API。
 */
class OptionalPhoneWriteEntryContractTest {
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private RegisterRequest register(String phone) {
        RegisterRequest request = new RegisterRequest();
        request.username = "player01";
        request.password = "password";
        request.nickname = "球员";
        request.phone = phone;
        request.email = "player01@example.test";
        return request;
    }

    private UpdateProfileRequest profile(String phone) {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.nickname = "球员";
        request.phone = phone;
        request.email = "player01@example.test";
        request.avatar = "https://example.test/avatar.png";
        return request;
    }

    private UpdateSystemUserRequest systemUser(String phone) {
        UpdateSystemUserRequest request = new UpdateSystemUserRequest();
        request.username = "player01";
        request.nickname = "球员";
        request.phone = phone;
        request.email = "player01@example.test";
        request.status = "ACTIVE";
        return request;
    }

    private Set<String> phoneViolations(Object target) {
        return validator.validate(target).stream()
            .filter(violation -> "phone".equals(violation.getPropertyPath().toString()))
            .map(violation -> violation.getMessage())
            .collect(Collectors.toSet());
    }

    @Test
    void allThreeWriteEntriesAcceptNullEmptyAndValidNumbers() {
        for (Object target : new Object[]{register(null), profile(null), systemUser(null)}) {
            assertThat(phoneViolations(target)).as("null 表示未填写").isEmpty();
        }
        for (Object target : new Object[]{register(""), profile(""), systemUser("")}) {
            assertThat(phoneViolations(target)).as("空字符串表示未填写").isEmpty();
        }
        for (Object target : new Object[]{
            register("13800138000"), profile("13800138000"), systemUser("13800138000")}) {
            assertThat(phoneViolations(target)).as("合法 11 位手机号").isEmpty();
        }
        for (Object target : new Object[]{
            register("13000000000"), profile("13999999999"), systemUser("19900000001")}) {
            assertThat(phoneViolations(target)).as("合法边界段").isEmpty();
        }
    }

    @Test
    void allThreeWriteEntriesRejectTheSameInvalidFormatsWithTheSameMessage() {
        for (String invalid : new String[]{"1", "12345", "abc123", "12800138000", "1380013800",
            "138001380001", "+8613800138000", "138 0013 8000", "1".repeat(32)}) {
            assertThat(phoneViolations(register(invalid)))
                .as("注册 API 拒绝 %s", invalid).containsExactly("请输入 11 位有效手机号");
            assertThat(phoneViolations(profile(invalid)))
                .as("个人资料 API 拒绝 %s", invalid).containsExactly("请输入 11 位有效手机号");
            assertThat(phoneViolations(systemUser(invalid)))
                .as("系统用户编辑 API 拒绝 %s", invalid).containsExactly("请输入 11 位有效手机号");
        }
    }

    @Test
    void unrelatedFieldsKeepTheirOwnContracts() {
        RegisterRequest register = register("13800138000");
        register.username = "u".repeat(65);
        register.nickname = "n".repeat(65);
        register.email = "a".repeat(120) + "@example.test";
        assertThat(validator.validate(register).stream()
            .map(violation -> violation.getPropertyPath().toString())
            .collect(Collectors.toSet())).contains("username", "nickname", "email");

        UpdateProfileRequest profile = profile("13800138000");
        profile.nickname = "n".repeat(65);
        profile.email = "not-an-email";
        assertThat(validator.validate(profile).stream()
            .map(violation -> violation.getPropertyPath().toString())
            .collect(Collectors.toSet())).contains("nickname", "email");

        UpdateSystemUserRequest systemUser = systemUser("13800138000");
        systemUser.username = "";
        systemUser.status = "";
        assertThat(validator.validate(systemUser).stream()
            .map(violation -> violation.getPropertyPath().toString())
            .collect(Collectors.toSet())).contains("username", "status");
    }
}
