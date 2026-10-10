package com.example.venue.common;

import java.util.List;

public class PageResult<T> {
    private List<T> records;
    private long total;
    private long pageNum;
    private long pageSize;

    public PageResult() {
    }

    public PageResult(List<T> records, long total, long pageNum, long pageSize) {
        this.records = records;
        this.total = total;
        this.pageNum = pageNum;
        this.pageSize = pageSize;
    }

    public static <T> PageResult<T> of(List<T> records, long total, long pageNum, long pageSize) {
        return new PageResult<>(records, total, pageNum, pageSize);
    }

    public List<T> getRecords() {
        return records;
    }

    public void setRecords(List<T> records) {
        this.records = records;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

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
