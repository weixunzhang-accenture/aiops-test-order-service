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
 * 不起 Spring 上下文：两个 {@code @Value} 字段直接注，测的是**类自己的行为**，
 * 毫秒级完成。要测装配/HTTP 层应另开 {@code @SpringBootTest}。
 */
class QuotationServiceTest {

    private static QuotationService service(Path tempDir, boolean tempLeak) {
        QuotationService svc = new QuotationService();
        ReflectionTestUtils.setField(svc, "tempDir", tempDir.toString());
        ReflectionTestUtils.setField(svc, "tempLeak", tempLeak);
        return svc;
    }

    private static long countOf(Path dir, String prefix) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefix)).count();
        }
    }

    @Test
    void generateQuotation_returnsContentMentioningTheOrder(@TempDir Path tmp) {
        byte[] pdf = service(tmp, false).generateQuotation("ORD-1001");

        assertThat(new String(pdf, StandardCharsets.UTF_8)).contains("ORD-1001");
    }

    @Test
    void generateQuotation_cleansUpItsTempFileByDefault(@TempDir Path tmp) throws IOException {
        service(tmp, false).generateQuotation("ORD-1002");

        // 正常路径：finally 里删掉临时文件，目录不该留东西
        assertThat(countOf(tmp, "quotation_")).isZero();
    }

    @Test
    void generateQuotation_cleansUpItsTempFileEvenWhenLeakSwitchIsOn(@TempDir Path tmp) throws IOException {
        // 修复后断言：即使历史开关 QUOTATION_TEMP_LEAK=true，临时文件也必须被清理 ——
        // 否则反复调用会无界累积写满 /data/tmp（本次故障根因：磁盘写满 → 报价单 500）。
        service(tmp, true).generateQuotation("ORD-1003");

        assertThat(countOf(tmp, "quotation_")).isZero();
    }

    @Test
    void generateQuotation_createsTempDirWhenMissing(@TempDir Path tmp) {
        Path nested = tmp.resolve("not/created/yet");
        assertThat(nested).doesNotExist();

        service(nested, false).generateQuotation("ORD-1004");

        assertThat(nested).exists();
    }

    @Test
    void generateQuotation_failsFastWithoutLeftoverWhenFreeSpaceBelowThreshold(@TempDir Path tmp) throws IOException {
        QuotationService svc = service(tmp, false);
        // 阈值抬到不可能满足的值，等价于模拟「临时目录即将写满」
        ReflectionTestUtils.setField(svc, "minFreeBytes", Long.MAX_VALUE);

        // 资源不足应当是独立的异常类型（HTTP 层映射 503 + Retry-After），而不是业务失败
        assertThatThrownBy(() -> svc.generateQuotation("ORD-1005"))
                .isInstanceOf(QuotationUnavailableException.class);
        // 快速失败：连半截临时文件都不该留下
        assertThat(countOf(tmp, "quotation_")).isZero();
    }

    @Test
    void leakFiles_createsRequestedNumberOfFiles(@TempDir Path tmp) throws IOException {
        int created = service(tmp, false).leakFiles(3, 1);

        assertThat(created).isEqualTo(3);
        assertThat(countOf(tmp, "leak_")).isEqualTo(3);
        // 与报价单是两套文件，别互相混淆
        assertThat(countOf(tmp, "quotation_")).isZero();
    }
}
