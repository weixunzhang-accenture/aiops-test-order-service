package com.company.order.service;

/**
 * 报价单落盘失败（临时目录可用空间不足 / 写入时磁盘 IO 失败）。
 *
 * <p>语义上与业务性失败（{@link QuotationException}，映射为 500）区分：这是**存储暂时不可用**，
 * 调用方等待磁盘释放后**可重试**。由 {@code GlobalExceptionHandler} 映射为
 * HTTP 503 + {@code Retry-After} + 响应体 {@code retryable:true}。
 *
 * <p>背景：磁盘写满（java.io.IOException: No space left on device）原先被
 * {@link QuotationException} 统一包装成 500，调用方无法区分「重试无用」与「重试有效」。
 */
public class QuotationStorageException extends RuntimeException {

    private final boolean retryable;

    public QuotationStorageException(String message, Throwable cause) {
        this(message, cause, true);
    }

    public QuotationStorageException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    /** 是否为可重试错误（磁盘/存储类失败恒为 true；写权限等不可恢复错误可为 false）。 */
    public boolean isRetryable() {
        return retryable;
    }
}
