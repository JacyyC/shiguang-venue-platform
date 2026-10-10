package com.example.venue.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAccountPasswordTest {
    @Test
    void migrationDefaultPasswordMatchesDocumentedPassword() {
        String passwordHash = "$2a$10$bBZklGMIFdu55euAGSmIdeHtjT6HlVmK7awTemoxDT3x4apR5lrwK";

        assertThat(new BCryptPasswordEncoder().matches("password", passwordHash)).isTrue();
    }
}
