package com.example.venue.payment.provider.alipay;

public class AlipaySandboxException extends IllegalStateException {
    private final String code;

    private AlipaySandboxException(String code) {
        super(code);
        this.code = code;
    }

    public static AlipaySandboxException config(String code) {
        return new AlipaySandboxException(code);
    }

    public String getCode() {
        return code;
    }
}
