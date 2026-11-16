package com.company.order.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QuotationService 单元测试（场景1 的被测服务）。
 *
 * 不起 Spring 上下文：{@code @Value} 字段直接注，测的是**类自己的行为**，
 * 毫秒级完成。要测装配/HTTP 层应另开 {@code @SpringBootTest}。
 *
 * <p>随修复同步的断言变化：原 {@code quotation.temp-leak} 开关已删除，
 * 临时文件在**任何**路径下都必须被清理，因此不再有「开关打开时留 1 个文件」的特征化测试；
 * 取而代之的是「反复调用不累积」与「可用空间不足提前失败且不留文件」两组断言。
 */
class QuotationServiceTest {

    private static QuotationService service(Path tempDir) {
        QuotationService svc = new QuotationService();
        ReflectionTestUtils.setField(svc, "tempDir", tempDir.toString());
        return svc;
    }

    private static QuotationService serviceRequiringFreeBytes(Path tempDir, long minFreeBytes) {
        QuotationService svc = service(tempDir);
        ReflectionTestUtils.setField(svc, "minFreeBytes", minFreeBytes);
        return svc;
    }

    private static long countOf(Path dir, String prefix) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefix)).count();
        }
    }

    @Test
    void generateQuotation_returnsContentMentioningTheOrder(@TempDir Path tmp) {
        byte[] pdf = service(tmp).generateQuotation("ORD-1001");

        assertThat(new String(pdf, StandardCharsets.UTF_8)).contains("ORD-1001");
    }

    @Test
    void generateQuotation_cleansUpItsTempFileByDefault(@TempDir Path tmp) throws IOException {
        service(tmp).generateQuotation("ORD-1002");

        // 正常路径：finally 里删掉临时文件，目录不该留东西
        assertThat(countOf(tmp, "quotation_")).isZero();
    }

    @Test
    void generateQuotation_doesNotAccumulateTempFilesAcrossCalls(@TempDir Path tmp) throws IOException {
        // 修复的核心回归点：清理不再受开关控制，调用 N 次后 /data/tmp 占用不随请求数增长
        QuotationService svc = service(tmp);
        for (int i = 0; i < 50; i++) {
            svc.generateQuotation("ORD-200" + i);
        }

        assertThat(countOf(tmp, "quotation_")).isZero();
    }

    @Test
    void generateQuotation_createsTempDirWhenMissing(@TempDir Path tmp) {
        Path nested = tmp.resolve("not/created/yet");
        assertThat(nested).doesNotExist();

        service(nested).generateQuotation("ORD-1004");

        assertThat(nested).exists();
    }

    @Test
    void generateQuotation_failsFastWithStorageExceptionWhenFreeSpaceIsInsufficient(@TempDir Path tmp)
            throws IOException {
        // 把「最小可用空间」抬到不可能满足的值，模拟卷接近写满：应提前失败，且不落任何文件
        QuotationService svc = serviceRequiringFreeBytes(tmp, Long.MAX_VALUE);

        assertThatThrownBy(() -> svc.generateQuotation("ORD-1005"))
                .isInstanceOf(QuotationStorageException.class)
                .hasMessageContaining("可用空间不足");
        assertThat(countOf(tmp, "quotation_")).isZero();
    }

    @Test
    void leakFiles_createsRequestedNumberOfFiles(@TempDir Path tmp) throws IOException {
        int created = service(tmp).leakFiles(3, 1);

        assertThat(created).isEqualTo(3);
        assertThat(countOf(tmp, "leak_")).isEqualTo(3);
        // 与报价单是两套文件，别互相混淆
        assertThat(countOf(tmp, "quotation_")).isZero();
    }
}
