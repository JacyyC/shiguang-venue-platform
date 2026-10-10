package com.example.venue.user.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = Utf8ByteSizeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface Utf8ByteSize {
    String message() default "密码 UTF-8 字节长度不能超过 72";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
    int max();
}
