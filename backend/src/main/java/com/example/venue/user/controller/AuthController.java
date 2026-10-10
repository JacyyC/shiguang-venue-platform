package com.example.venue.user.controller;

import com.example.venue.common.Result;
import com.example.venue.common.Constants;
import com.example.venue.user.dto.AuthResponse;
import com.example.venue.user.dto.ForgotPasswordRequest;
import com.example.venue.user.dto.LoginRequest;
import com.example.venue.user.dto.PasswordResetRequestResponse;
import com.example.venue.user.dto.RegisterRequest;
import com.example.venue.user.dto.ResetPasswordRequest;
import com.example.venue.user.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Result<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return Result.ok(authService.login(request, httpRequest));
    }

    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return Result.ok();
    }

    @PostMapping("/forgot-password")
    public Result<PasswordResetRequestResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest httpRequest) {
        return Result.ok(authService.requestPasswordReset(request, httpRequest));
    }

    @PostMapping("/reset-password")
    public Result<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return Result.ok();
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest httpRequest) {
        authService.logout(httpRequest.getHeader(Constants.AUTH_HEADER));
        return Result.ok();
    }
}
