package com.example.flowtracker.compat;

import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowCompatTest {

    @Test
    void saverDescriptorWorksWithTheIdeItIsCompiledAgainst() {
        // The tests run against IntelliJ IDEA 2023.1, which only has the String... constructor: the fallback is used.
        FileSaverDescriptor d = FlowCompat.saverDescriptor("Export Flow", "Save this flow", "json");
        assertEquals("Export Flow", d.getTitle());
        assertEquals("Save this flow", d.getDescription());
        assertArrayEquals(new String[]{"json"}, d.getFileExtensions());
    }
}
