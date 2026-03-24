package com.urlshortener.unit;

import com.urlshortener.service.Base62CodeGenerator;
import com.urlshortener.service.CodeGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CodeGenerator")
class CodeGeneratorTest {

    private CodeGenerator codeGenerator;

    @BeforeEach
    void setUp() {
        codeGenerator = new Base62CodeGenerator();
    }

    @Test
    @DisplayName("generated code has correct length")
    void generatedCode_hasCorrectLength() {
        String code = codeGenerator.generate();
        assertThat(code).hasSize(CodeGenerator.CODE_LENGTH);
    }

    @Test
    @DisplayName("generated code uses only Base62 characters")
    void generatedCode_usesOnlyBase62Charset() {
        String code = codeGenerator.generate();
        assertThat(code).matches("[a-zA-Z0-9]+");
    }

    @Test
    @DisplayName("1000 generated codes are all unique")
    void generatedCodes_areUnique() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            codes.add(codeGenerator.generate());
        }
        assertThat(codes).hasSize(1000);
    }

    @Test
    @DisplayName("consecutive calls return different codes")
    void consecutiveCalls_returnDifferentCodes() {
        String first = codeGenerator.generate();
        String second = codeGenerator.generate();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("alphabet contains exactly 62 characters")
    void alphabet_hasExactly62Characters() {
        assertThat(CodeGenerator.ALPHABET).hasSize(62);
    }

    @Test
    @DisplayName("alphabet contains no duplicate characters")
    void alphabet_hasNoDuplicates() {
        long distinctChars = CodeGenerator.ALPHABET.chars().distinct().count();
        assertThat(distinctChars).isEqualTo(62);
    }
}
