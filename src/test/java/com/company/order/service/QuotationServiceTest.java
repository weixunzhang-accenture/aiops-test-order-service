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
    void generateQuotation_cleansUpTempFileEvenWhenLeakSwitchIsOn(@TempDir Path tmp) throws IOException {
        // 修复后：QUOTATION_TEMP_LEAK 不再影响清理逻辑，开关即使为 true 也不留文件，
        // 避免反复调用把 /data 写满（原场景1 主因）。
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
    void quotationSummary_failsWithBusinessErrorWhenTemplateMissing(@TempDir Path tmp) {
        // 模板加载返回 null 时，应抛可定位的业务异常而不是 NPE。
        assertThatThrownBy(() -> service(tmp, false).quotationSummary("ORD-1005"))
                .isInstanceOf(QuotationException.class)
                .hasMessageContaining("模板缺失");
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
