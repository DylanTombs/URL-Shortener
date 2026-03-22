package com.urlshortener.unit;

import com.urlshortener.service.CodeGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CodeGenerator")
class CodeGeneratorTest {

    private CodeGenerator codeGenerator;

    @BeforeEach
    void setUp() {
        codeGenerator = new CodeGenerator();
    }

    @Test
    @DisplayName("generated code has correct length")
    void generatedCode_hasCorrectLength() {
        // TODO: uncomment when CodeGenerator.generate() is implemented
        // String code = codeGenerator.generate();
        // assertThat(code).hasSize(CodeGenerator.CODE_LENGTH);
    }

    @Test
    @DisplayName("generated code uses only Base62 characters")
    void generatedCode_usesOnlyBase62Charset() {
        // TODO: uncomment when implemented
        // String code = codeGenerator.generate();
        // assertThat(code).matches("[a-zA-Z0-9]+");
    }

    @RepeatedTest(1000)
    @DisplayName("collision probability: 1000 codes are unique")
    void generatedCodes_areUnique() {
        // TODO: uncomment when implemented
        // Set<String> codes = new HashSet<>();
        // for (int i = 0; i < 1000; i++) {
        //     codes.add(codeGenerator.generate());
        // }
        // assertThat(codes).hasSize(1000);
    }
}
