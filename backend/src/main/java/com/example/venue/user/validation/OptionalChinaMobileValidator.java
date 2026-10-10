package com.example.venue.user.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

/**
 * {@link OptionalChinaMobile} 的校验实现：空白视为未填写，非空值必须匹配大陆手机号口径。
 */
public class OptionalChinaMobileValidator implements ConstraintValidator<OptionalChinaMobile, String> {
    /** 与预约联系人共享同一口径；用户可选手机号与必填联系人手机号不引入第二套正则。 */
    private static final Pattern CHINA_MOBILE = Pattern.compile("^1[3-9]\\d{9}$");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return CHINA_MOBILE.matcher(value.trim()).matches();
    }
}
