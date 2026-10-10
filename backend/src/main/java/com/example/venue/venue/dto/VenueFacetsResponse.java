package com.example.venue.venue.dto;

import java.util.List;

public record VenueFacetsResponse(
    List<String> areas,
    List<String> spaceTypes
) {
}
