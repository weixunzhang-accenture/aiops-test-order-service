package com.company.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * 报价单生成（场景1 被测）。
 * 纯字节流写临时文件到 /data/tmp。
 */
@Service
public class QuotationService {

    private static final Logger log = LoggerFactory.getLogger(QuotationService.class);

    @Value("${quotation.temp-dir:/data/tmp}")
    private String tempDir;

    // ⚠️ 场景1 bug 开关：QUOTATION_TEMP_LEAK=true 时泄漏临时文件（不清理）→ 磁盘写满
    @Value("${quotation.temp-leak:false}")
    private boolean tempLeak;

    /** 落盘前要求的最小可用空间（默认 1MB），低于此值直接判定存储不可用。 */
    @Value("${quotation.min-free-bytes:1048576}")
    private long minFreeBytes;

    public byte[] generateQuotation(String orderId) {
        File dir = new File(tempDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        if (!dir.isDirectory()) {
            log.error("报价单临时目录不可用 tempDir={}", tempDir);
            throw new QuotationStorageException("报价单临时目录不可用: " + tempDir, null, false);
        }

        // 落盘前预检可用空间：磁盘写满时**不要**等 FileOutputStream 抛 IOException，
        // 提前失败并给出明确语义（503 + 可重试），而不是含糊的 500。
        long usableBytes = dir.getUsableSpace();
        if (usableBytes >= 0 && usableBytes < minFreeBytes) {
            log.error("报价单临时目录可用空间不足 usableBytes={} requiredBytes={} tempDir={}",
                    usableBytes, minFreeBytes, tempDir);
            throw new QuotationStorageException(
                    "报价单临时目录可用空间不足（usable=" + usableBytes + "B, required=" + minFreeBytes + "B）",
                    null);
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
            log.error("生成报价单失败: {}", e.toString(), e);
            if (isOutOfSpace(e)) {
                // 磁盘/存储不可用：语义上可重试，交给上层映射为 503，别被 QuotationException(500) 吞掉
                throw new QuotationStorageException(
                        "报价单临时目录写入失败：磁盘空间不足（tempDir=" + tempDir + "）", e);
            }
            throw new QuotationException("生成报价单失败", e);
        } finally {
            // ⚠️ bug 注入点：tempLeak=true 时不删除临时文件，模拟「finally 未清理」
            if (tmp != null && tmp.exists() && !tempLeak) {
                tmp.delete();
            }
        }
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

    /** 判断 IOException 是否为磁盘空间耗尽（含 cause 链）。 */
    private static boolean isOutOfSpace(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (lower.contains("no space left on device") || lower.contains("enospc")
                        || lower.contains("磁盘空间不足")) {
                    return true;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

}
