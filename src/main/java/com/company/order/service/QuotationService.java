package com.company.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 报价单生成（场景1 被测）。
 * 纯字节流写临时文件到 /data/tmp。
 */
@Service
public class QuotationService {

    private static final Logger log = LoggerFactory.getLogger(QuotationService.class);

    /** 单请求临时文件输出的字节上限：超出即快速失败，避免单请求把 /data 写满。 */
    private static final long MAX_TEMP_FILE_BYTES = 8L * 1024 * 1024;

    /** 写入前要求的最小可用空间：低于此值快速失败，避免抛 java.io.IOException: No space left on device。 */
    private static final long MIN_USABLE_SPACE_BYTES = 16L * 1024 * 1024;

    @Value("${quotation.temp-dir:/data/tmp}")
    private String tempDir;

    // QUOTATION_TEMP_LEAK 历史上为 true 时跳过临时文件清理，反复调用会持续占用 /data，
    // 最终磁盘写满（场景1 主因）。该开关现已失效：无论取何值，临时文件都在请求结束
    // （含异常路径）后被清理，保留字段仅为兼容既有配置注入。
    @Value("${quotation.temp-leak:false}")
    private boolean tempLeak;

    public byte[] generateQuotation(String orderId) {
        File dir = new File(tempDir);
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new QuotationException("临时目录不可用: " + tempDir, null);
        }
        // 写入前校验可用空间：不足时给出明确语义，而不是等到写盘时抛 ENOSPC。
        ensureUsableSpace(dir);

        byte[] content = ("Quotation for order " + orderId + " @ " + System.currentTimeMillis() + "\n")
                .getBytes(StandardCharsets.UTF_8);
        if (content.length > MAX_TEMP_FILE_BYTES) {
            throw new QuotationException("报价单内容超出单请求上限: " + content.length + " bytes", null);
        }

        File tmp = null;
        try {
            tmp = File.createTempFile("quotation_" + orderId + "_", ".pdf", dir);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(content);
            }
            log.info("报价单生成成功 orderId={} file={}", orderId, tmp.getName());
            return content;
        } catch (IOException e) {
            log.error("生成报价单失败: {}", e.toString(), e);
            throw new QuotationException("生成报价单失败", e);
        } finally {
            // 修复：无论 tempLeak 取何值，临时文件都必须在请求结束（含异常路径）后被删除，
            // 否则反复调用会持续占用 /data，最终 ENOSPC 打满磁盘导致 500。
            if (tmp != null && tmp.exists()) {
                if (!tmp.delete()) {
                    log.warn("临时文件清理失败 file={}", tmp.getAbsolutePath());
                }
            }
        }
    }

    /** 写入前可用空间校验：不足时快速失败并抛出可识别的业务异常（而非底层 IOException）。 */
    private void ensureUsableSpace(File dir) {
        long usable = dir.getUsableSpace();
        if (usable >= 0 && usable < MIN_USABLE_SPACE_BYTES) {
            log.error("临时目录可用空间不足 dir={} usable={} bytes", dir.getAbsolutePath(), usable);
            throw new QuotationException("临时目录可用空间不足: " + usable + " bytes", null);
        }
    }

    /** 报价单摘要（场景3 被测）。 */
    public String quotationSummary(String orderId) {
        String template = loadTemplate(orderId);
        if (template == null) {
            // 修复：模板缺失时返回明确业务错误并记录模板名，避免 template.trim() 抛 NPE
            // 被 GlobalExceptionHandler 兜成无定位信息的 500。
            log.error("报价单模板缺失 orderId={} template={}", orderId, templateName(orderId));
            throw new QuotationException("报价单模板缺失 orderId=" + orderId, null);
        }
        log.info("渲染报价单摘要 orderId={}", orderId);
        return template.trim();
    }

    private String templateName(String orderId) {
        return "quotation-" + orderId;
    }

    /** 模拟模板仓库/配置中心查询：查不到时返回 null（不做兜底） */
    private String loadTemplate(String orderId) {
        log.warn("报价单模板加载失败，返回 null orderId={}", orderId);
        return null;
    }

    /** 供 /test/leak 快速填盘 */
    public int leakFiles(int count, int sizeMb) throws IOException {
        File dir = new File(tempDir);
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("临时目录不可用: " + tempDir);
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
