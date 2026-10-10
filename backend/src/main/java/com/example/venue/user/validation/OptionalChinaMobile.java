package com.example.venue.user.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 可选中国大陆手机号：null、空字符串与纯空白表示「未填写」；非空值必须是 11 位大陆手机号。
 *
 * <p>与预约联系人已采用的 {@code ^1[3-9]\d{9}$} 口径一致，用作注册、个人资料和系统用户编辑
 * 三个写入口共享的数据不变量；前端只能对齐该约束，不能替代服务端校验。</p>
 */
@Documented
@Constraint(validatedBy = OptionalChinaMobileValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface OptionalChinaMobile {
    String message() default "请输入 11 位有效手机号";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
