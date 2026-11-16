package com.company.order.service;

/**
 * 报价单临时目录不可用（空间不足 / 不可写）。
 *
 * <p>与 {@link QuotationException} 的区分：本异常表示**依赖的存储不可用**，语义上属于
 * 「暂时不可用、可重试」，由 {@code GlobalExceptionHandler} 映射为 503；而
 * {@link QuotationException} 表示应用自身的未预期故障（500）。
 *
 * <p>引入它的目的是消除事故里的对外表现：{@code /data} 写满时原本只抛出裸
 * {@code IOException: No space left on device}，被包装后由全局处理器转成**无任何业务语义的 500**，
 * 排查只能靠翻堆栈。现在磁盘不足是一条可识别、带固定日志签名的存储故障，
 * 既便于监控打点，也不会与真正的代码缺陷混在同一个错误分类里。
 */
public class QuotationStorageException extends RuntimeException {

    public QuotationStorageException(String message) {
        super(message);
    }

    public QuotationStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
