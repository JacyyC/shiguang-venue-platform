package com.example.venue.system.dto;

import jakarta.validation.constraints.Size;
import com.example.venue.user.validation.Utf8ByteSize;

public class ResetUserPasswordRequest {
    @Size(min = 6, max = 64)
    @Utf8ByteSize(max = 72)
    public String password;
}
