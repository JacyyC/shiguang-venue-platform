package com.example.venue.user.dto;

import java.util.Set;

public class AuthResponse {
    public String token;
    public long expiresIn;
    public UserProfile user;
    public Set<String> roles;
    public Set<String> permissions;

    public AuthResponse(String token, long expiresIn, UserProfile user, Set<String> roles, Set<String> permissions) {
        this.token = token;
        this.expiresIn = expiresIn;
        this.user = user;
        this.roles = roles;
        this.permissions = permissions;
    }
}
