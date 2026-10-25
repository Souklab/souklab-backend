package com.project.souklab.filestorage.scan;

import java.io.IOException;

import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VirusScannerTest {

    @Test
    void byteArrayOverloadDelegatesToStreamScanner() {
        VirusScanner scanner = content -> {
            try {
                return content.read() == 'o' ? ScanResult.clean() : ScanResult.error("unexpected");
            } catch (IOException exception) {
                return ScanResult.error(exception.getMessage());
            }
        };

        assertThat(scanner.scan("ok".getBytes()).isClean()).isTrue();
    }

    @Test
    void byteArrayOverloadRejectsNull() {
        VirusScanner scanner = content -> ScanResult.clean();

        assertThatThrownBy(() -> scanner.scan((byte[]) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Byte array content cannot be null");
    }
}
