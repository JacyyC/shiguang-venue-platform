package com.example.venue.placement.service;

import com.example.venue.placement.source.PlacementSourceSnapshot;
import com.example.venue.placement.source.PlacementSourceType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlacementTokenServiceTest {

    private final PlacementTokenService tokenService = new PlacementTokenService();

    @Test
    void generatesUrlSafeBase64TokenAndSha256Hash() {
        PlacementTokenService.GeneratedToken token = tokenService.generate();

        assertThat(token.raw()).isNotBlank();
        assertThat(token.hash()).hasSize(64);
        assertThat(tokenService.hash(token.raw())).isEqualTo(token.hash());
    }

    @Test
    void generatedTokensAreUniqueAndNonEnumerable() {
        Set<String> raws = new HashSet<>();
        Set<String> hashes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            PlacementTokenService.GeneratedToken token = tokenService.generate();
            raws.add(token.raw());
            hashes.add(token.hash());
        }
        assertThat(raws).hasSize(200);
        assertThat(hashes).hasSize(200);
    }

    @Test
    void hashReturnsNullForBlankInput() {
        assertThat(tokenService.hash(null)).isNull();
        assertThat(tokenService.hash("")).isNull();
        assertThat(tokenService.hash("   ")).isNull();
    }

    @Test
    void earliestExpiryPicksTheEarliestAmongTokenTtlCandidateAndSource() {
        LocalDateTime issued = LocalDateTime.of(2030, 1, 1, 0, 0);
        PlacementSourceSnapshot source = new PlacementSourceSnapshot(
            PlacementSourceType.GLOBAL_NOTICE, 1L, "v1", "title", "summary",
            null, issued, issued.plusHours(2), "/me/messages");

        // token TTL is 10 minutes -> 00:10; source ends 02:00; candidate ends 01:00
        LocalDateTime result = tokenService.earliestExpiry(issued, issued.plusHours(1), source);
        assertThat(result).isEqualTo(issued.plusMinutes(10));

        // candidate ends before token TTL
        LocalDateTime result2 = tokenService.earliestExpiry(issued, issued.plusMinutes(3), source);
        assertThat(result2).isEqualTo(issued.plusMinutes(3));
    }

    @Test
    void earliestExpiryPicksSourceEndWhenEarlierThanTokenTtl() {
        LocalDateTime issued = LocalDateTime.of(2030, 1, 1, 0, 0);
        PlacementSourceSnapshot source = new PlacementSourceSnapshot(
            PlacementSourceType.GLOBAL_NOTICE, 1L, "v1", "title", "summary",
            null, issued, issued.plusMinutes(5), "/me/messages");

        LocalDateTime result = tokenService.earliestExpiry(issued, issued.plusHours(1), source);
        assertThat(result).isEqualTo(issued.plusMinutes(5));
    }

    @Test
    void earliestExpiryIgnoresNullCandidateAndSourceBounds() {
        LocalDateTime issued = LocalDateTime.of(2030, 1, 1, 0, 0);

        LocalDateTime result = tokenService.earliestExpiry(issued, null, null);
        assertThat(result).isEqualTo(issued.plusMinutes(10));
    }
}
