package com.example.venue.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.example.venue.user.validation.Utf8ByteSize;

public class ChangePasswordRequest {
    @NotBlank
    public String oldPassword;
    @NotBlank
    @Size(min = 6, max = 64)
    @Utf8ByteSize(max = 72)
    public String newPassword;
}
