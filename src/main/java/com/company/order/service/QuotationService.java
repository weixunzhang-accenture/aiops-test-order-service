package com.company.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/**
 * 报价单生成（场景1 被测）。
 * 纯字节流写临时文件到 /data/tmp。
 */
@Service
public class QuotationService {

    private static final Logger log = LoggerFactory.getLogger(QuotationService.class);

    /** 默认最小可用空间：低于它不再尝试写入，快速失败而不是写半截文件（32MB）。 */
    private static final long DEFAULT_MIN_FREE_BYTES = 32L * 1024 * 1024;

    @Value("${quotation.temp-dir:/data/tmp}")
    private String tempDir;

    /** 写临时文件前要求的最小可用空间，可配置。 */
    @Value("${quotation.min-free-bytes:33554432}")
    private long minFreeBytes = DEFAULT_MIN_FREE_BYTES;

    /**
     * 历史开关（QUOTATION_TEMP_LEAK）。**已废弃、不再生效**：临时文件无论成功还是异常路径
     * 都必须被清理，否则反复调用会无界累积写满 /data/tmp（本次故障根因）。
     * 字段保留仅为兼容既有配置与测试装配，写入时会打一条 WARN。
     */
    @Value("${quotation.temp-leak:false}")
    private boolean tempLeak;

    public byte[] generateQuotation(String orderId) {
        File dir = new File(tempDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        if (!dir.isDirectory()) {
            throw unavailable("报价单临时目录不可用 dir=" + tempDir, null);
        }
        if (tempLeak) {
            log.warn("quotation.temp-leak 已废弃：临时文件始终会清理，开关被忽略 orderId={}", orderId);
        }

        // 写前检查可用空间：磁盘/临时目录将满时直接快速失败，不写半截文件、不留下垃圾文件。
        long required = minFreeBytes > 0 ? minFreeBytes : DEFAULT_MIN_FREE_BYTES;
        long usable = dir.getUsableSpace();
        if (usable < required) {
            throw unavailable(String.format(
                    "报价单临时目录空间不足 dir=%s usable=%d required=%d", tempDir, usable, required), null);
        }

        File tmp = null;
        try {
            tmp = File.createTempFile("quotation_" + orderId + "_", ".pdf", dir);
            byte[] content = ("Quotation for order " + orderId + " @ " + System.currentTimeMillis() + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(content);
            }
            log.info("报价单生成成功 orderId={} file={}", orderId, tmp.getName());
            return content;
        } catch (IOException e) {
            if (isOutOfSpace(e)) {
                // 资源不足 ≠ 业务失败：交给上层以 503 + Retry-After 暴露，调用方可重试
                throw unavailable("报价单临时文件写入失败：磁盘空间不足 file=" + tmp, e);
            }
            log.error("生成报价单失败: {}", e.toString(), e);
            throw new QuotationException("生成报价单失败", e);
        } finally {
            // 成功、业务异常、资源异常三种路径都清理临时文件，避免 /data/tmp 无界累积撑满磁盘
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp.toPath());
                } catch (IOException cleanupFailure) {
                    log.warn("临时文件清理失败 file={}", tmp.getAbsolutePath(), cleanupFailure);
                }
            }
        }
    }

    private static QuotationUnavailableException unavailable(String message, Throwable cause) {
        log.error("报价单生成不可用: {}", message, cause);
        return new QuotationUnavailableException(message, cause);
    }

    /** IOException 链路上是否命中「磁盘写满」形态。 */
    private static boolean isOutOfSpace(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg == null) {
                continue;
            }
            String lower = msg.toLowerCase();
            if (lower.contains("no space left on device")
                    || lower.contains("enospc")
                    || lower.contains("disk quota exceeded")
                    || lower.contains("not enough space")) {
                return true;
            }
        }
        return false;
    }

    /** 报价单摘要（场景3 被测）。 */
    public String quotationSummary(String orderId) {
        String template = loadTemplate(orderId);
        log.info("渲染报价单摘要 orderId={}", orderId);
        // ⚠️ 场景3 bug 注入点：模板加载失败返回 null，这里未判空直接调用 → NullPointerException
        return template.trim();
    }

    /** 模拟模板仓库/配置中心查询：查不到时返回 null（不做兜底） */
    private String loadTemplate(String orderId) {
        log.warn("报价单模板加载失败，返回 null orderId={}", orderId);
        return null;
    }

    /** 供 /test/leak 快速填盘 */
    public int leakFiles(int count, int sizeMb) throws IOException {
        File dir = new File(tempDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        byte[] chunk = new byte[sizeMb * 1024 * 1024];
        int created = 0;
        for (int i = 0; i < count; i++) {
            File f = new File(dir, "leak_" + UUID.randomUUID() + ".bin");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(chunk);
            }
            created++;
        }
        log.warn("leakFiles 写入 {} 个文件（各 {}MB）", created, sizeMb);
        return created;
    }

}
