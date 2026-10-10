package com.example.venue.payment.provider;

import com.example.venue.payment.dto.PaymentCallbackCommand;
import com.example.venue.payment.dto.PaymentCallbackEnvelope;
import com.example.venue.payment.dto.PaymentCallbackResult;
import com.example.venue.payment.dto.PaymentCloseCommand;
import com.example.venue.payment.dto.PaymentCloseResult;
import com.example.venue.payment.dto.PaymentCreateCommand;
import com.example.venue.payment.dto.PaymentCreateResult;
import com.example.venue.payment.dto.ProviderStatementItem;
import com.example.venue.payment.dto.ProviderStatementQuery;
import com.example.venue.payment.dto.VerifiedPaymentCallback;

import java.util.List;

public interface PaymentProvider {
    PaymentProviderDescriptor descriptor();

    PaymentCreateResult createPayment(PaymentCreateCommand command);

    default PaymentCloseResult closePayment(PaymentCloseCommand command) {
        throw new UnsupportedOperationException("payment close is not supported");
    }

    PaymentCallbackResult handleCallback(PaymentCallbackCommand command);

    default VerifiedPaymentCallback verifyCallback(PaymentCallbackEnvelope envelope) {
        throw new UnsupportedOperationException("raw callback is not supported");
    }

    List<ProviderStatementItem> statement(ProviderStatementQuery query);
}
