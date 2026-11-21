package com.orderaudit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guard test: money and quantities must never use floating point.
 * It reads every main source file and fails if it finds double, float, Double or Float
 * (comments are removed first, so words in comments are fine).
 */
class NoFloatingPointGuardTest {

    private static final Pattern FORBIDDEN = Pattern.compile("\\b(double|float|Double|Float)\\b");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    @Test
    void mainSourceCodeContainsNoFloatingPointTypes() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = Files.readString(file);
                code = BLOCK_COMMENT.matcher(code).replaceAll("");
                code = LINE_COMMENT.matcher(code).replaceAll("");
                if (FORBIDDEN.matcher(code).find()) {
                    offenders.add(file.toString());
                }
            }
        }
        assertThat(offenders).as("files using floating point").isEmpty();
    }

    @Test
    void guardActuallyDetectsFloatingPoint() {
        assertThat(FORBIDDEN.matcher("double price = 1.5;").find()).isTrue();
        assertThat(FORBIDDEN.matcher("BigDecimal price;").find()).isFalse();
    }
}
