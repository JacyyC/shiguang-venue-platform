package com.example.venue.provider.weather;

public interface WeatherProvider {
    String providerKey();

    String providerEnv();

    String providerName();

    String configVersion();

    WeatherProviderResult fetch(WeatherProviderRequest request);
}
