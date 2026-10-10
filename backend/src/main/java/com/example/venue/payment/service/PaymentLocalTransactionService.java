package com.example.venue.payment.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starts a short local payment transaction after Provider verification has completed.
 */
@Service
public class PaymentLocalTransactionService {
    @Transactional
    public void execute(Runnable work) {
        work.run();
    }
}
