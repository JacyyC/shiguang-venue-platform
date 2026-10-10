package com.example.venue.common;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public class PageQuery {
    @Min(1)
    private long pageNum = 1;
    @Min(1)
    @Max(100)
    private long pageSize = 10;

    public long getPageNum() {
        return pageNum;
    }

    public void setPageNum(long pageNum) {
        this.pageNum = pageNum;
    }

    public long getPageSize() {
        return pageSize;
    }

    public void setPageSize(long pageSize) {
        this.pageSize = pageSize;
    }
}
