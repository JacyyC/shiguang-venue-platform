package com.example.venue.resource.exception;

import com.example.venue.common.BusinessException;
import com.example.venue.common.ResultCode;
import com.example.venue.resource.enums.AllocationConflictCode;

public class ResourceAllocationConflictException extends BusinessException {
    private final AllocationConflictCode conflictCode;

    public ResourceAllocationConflictException(AllocationConflictCode conflictCode, String message) {
        super(ResultCode.CONFLICT, message);
        this.conflictCode = conflictCode;
    }

    public AllocationConflictCode getConflictCode() {
        return conflictCode;
    }
}
