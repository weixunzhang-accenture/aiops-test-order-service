package com.company.order.service;

/**
 * 「资源不足」类故障：报价单临时目录/节点磁盘空间耗尽（{@code No space left on device}）。
 *
 * <p>与业务失败（{@link QuotationException}）刻意区分：这不是报价单本身有问题，
 * 而是节点级临时故障，调用方应视为**可重试**，HTTP 层映射为 503 + {@code Retry-After}，
 * 而不是笼统的 500。
 */
public class QuotationUnavailableException extends RuntimeException {

    public QuotationUnavailableException(String message) {
        super(message);
    }

    public QuotationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
