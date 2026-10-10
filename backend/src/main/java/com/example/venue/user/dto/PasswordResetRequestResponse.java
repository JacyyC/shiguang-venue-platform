package com.example.venue.user.dto;

/**
 * 找回密码请求响应。
 *
 * <p>AUD-P1-03 止血（S03a）：DEV 环境配置闸门开启时返回 token 原文，便于联调闭环；
 * 生产环境 token 为 null，改由通知渠道投递（S03b 邮件化）。</p>
 * <p>响应体经 Jackson non_null 配置，token 为 null 时不输出字段，避免泄露用户是否存在。</p>
 */
public class PasswordResetRequestResponse {
    /** DEV 止血返回的重置 token 原文；生产环境为 null */
    public String token;

    public PasswordResetRequestResponse() {
    }

    public PasswordResetRequestResponse(String token) {
        this.token = token;
    }
}
