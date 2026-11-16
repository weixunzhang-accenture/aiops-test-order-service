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
 *
 * <h3>临时文件生命周期（本次修复要点）</h3>
 * <ul>
 *   <li>临时文件在 {@code finally} 中**无条件**删除：正常返回、异常、超时/中断路径都不残留，
 *       不再由 {@code quotation.temp-leak} 这类开关决定是否清理（此前该开关打开时
 *       {@code /data/tmp} 占用随请求数单调增长，直到把 512Mi 的 EmptyDir 写满）。</li>
 *   <li>落盘前做可用空间预检：空间不足直接抛 {@link QuotationStorageException}（可识别、可重试），
 *       而不是等 {@code FileOutputStream} 抛裸 {@code IOException: No space left on device}。</li>
 *   <li>写入过程中若仍撞上 ENOSPC（预检与写入之间的竞态），也归类为存储不可用并带上明确语义。</li>
 *   <li>临时文件名前缀沿用 {@code quotation_<orderId>_}，由 {@link File#createTempFile} 保证唯一，
 *       不自增、不累积。</li>
 * </ul>
 */
@Service
public class QuotationService {

    private static final Logger log = LoggerFactory.getLogger(QuotationService.class);

    @Value("${quotation.temp-dir:/data/tmp}")
    private String tempDir;

    /** 落盘前要求的最小可用空间（默认 1MiB）：低于此值直接判定存储不可用，提前失败。 */
    @Value("${quotation.min-free-bytes:1048576}")
    private long minFreeBytes = 1024L * 1024L;

    public byte[] generateQuotation(String orderId) {
        File dir = ensureTempDir();

        // 预检：磁盘/内存卷接近写满时不要靠 IOException 兜底，提前给出可识别的存储故障
        long usableBytes = dir.getUsableSpace();
        if (usableBytes >= 0 && usableBytes < minFreeBytes) {
            log.error("报价单临时目录可用空间不足 tempDir={} usableBytes={} requiredBytes={}",
                    tempDir, usableBytes, minFreeBytes);
            throw new QuotationStorageException("报价单临时目录可用空间不足（usable="
                    + usableBytes + "B, required=" + minFreeBytes + "B, tempDir=" + tempDir + "）");
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
                // 预检与写入之间仍可能被写满：归类为存储不可用（503），不让它伪装成应用缺陷
                log.error("报价单临时目录写入失败：磁盘空间不足 tempDir={} orderId={} err={}",
                        tempDir, orderId, e.toString(), e);
                throw new QuotationStorageException("报价单临时目录写入失败：磁盘空间不足（tempDir="
                        + tempDir + "）", e);
            }
            log.error("生成报价单失败: {}", e.toString(), e);
            throw new QuotationException("生成报价单失败", e);
        } finally {
            // 修复：无论正常/异常/超时路径都显式删除临时文件，避免 /data 占用随请求数增长
            deleteQuietly(tmp);
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
        File dir = ensureTempDir();
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

    /** 确保临时目录存在且可用；不可用时抛出可识别的存储故障。 */
    private File ensureTempDir() {
        File dir = new File(tempDir);
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            log.error("报价单临时目录不可用 tempDir={} exists={} writable={}",
                    tempDir, dir.exists(), dir.canWrite());
            throw new QuotationStorageException("报价单临时目录不可用（tempDir=" + tempDir + "）");
        }
        return dir;
    }

    /** 删除临时文件：失败只记 WARN（带文件名便于运维手工清理），不影响本次响应。 */
    private void deleteQuietly(File tmp) {
        if (tmp == null) {
            return;
        }
        if (!tmp.delete() && tmp.exists()) {
            log.warn("临时报价单文件删除失败 file={} size={}", tmp.getName(), tmp.length());
        }
    }

    /** 判断 IOException 是否为磁盘空间耗尽（含 cause 链）。 */
    private static boolean isOutOfSpace(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (lower.contains("no space left on device") || lower.contains("enospc")
                        || msg.contains("磁盘空间不足")) {
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
