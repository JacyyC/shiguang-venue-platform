package com.example.venue.notice.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.notice.smtp")
public class SmtpSandboxProperties {
    private boolean enabled;
    private boolean sandboxOnly = true;
    private String host = "127.0.0.1";
    private int port = 1025;
    private boolean auth;
    private boolean starttls;
    private String username = "";
    private String password = "";
    private String fromAddress = "no-reply@venue.local";
    private String fromName = "Venue Reservation Sandbox";
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 5000;
    private int writeTimeoutMs = 5000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isSandboxOnly() {
        return sandboxOnly;
    }

    public void setSandboxOnly(boolean sandboxOnly) {
        this.sandboxOnly = sandboxOnly;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isAuth() {
        return auth;
    }

    public void setAuth(boolean auth) {
        this.auth = auth;
    }

    public boolean isStarttls() {
        return starttls;
    }

    public void setStarttls(boolean starttls) {
        this.starttls = starttls;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public void setFromAddress(String fromAddress) {
        this.fromAddress = fromAddress;
    }

    public String getFromName() {
        return fromName;
    }

    public void setFromName(String fromName) {
        this.fromName = fromName;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public int getWriteTimeoutMs() {
        return writeTimeoutMs;
    }

    public void setWriteTimeoutMs(int writeTimeoutMs) {
        this.writeTimeoutMs = writeTimeoutMs;
    }
}
