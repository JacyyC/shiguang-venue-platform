package com.example.venue.payment.service;

import com.example.venue.order.entity.PaymentCallbackLog;
import com.example.venue.order.mapper.PaymentCallbackLogMapper;
import com.example.venue.payment.entity.PaymentProviderOperationLog;
import com.example.venue.payment.mapper.PaymentProviderOperationLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentCallbackLogService {
    private final PaymentCallbackLogMapper mapper;
    private final PaymentProviderOperationLogMapper operationLogMapper;
    private final TransactionTemplate requiresNew;

    public PaymentCallbackLogService(PaymentCallbackLogMapper mapper,
                                     PaymentProviderOperationLogMapper operationLogMapper,
                                     PlatformTransactionManager txManager) {
        this.mapper = mapper;
        this.operationLogMapper = operationLogMapper;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(PaymentCallbackLog log) {
        requiresNew.executeWithoutResult(status -> mapper.insert(log));
    }

    public void recordInCurrentTransaction(PaymentCallbackLog log) {
        mapper.insert(log);
    }

    public void recordOperation(PaymentProviderOperationLog log) {
        requiresNew.executeWithoutResult(status -> operationLogMapper.insert(log));
    }

    public void recordOperationInCurrentTransaction(PaymentProviderOperationLog log) {
        operationLogMapper.insert(log);
    }
}
