package com.example.gazo;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportFileTypeTest {

    @Test
    void isImage_recognizesCommonExtensions() {
        assertTrue(ImportFileType.isImage(Path.of("a.jpg")));
        assertTrue(ImportFileType.isImage(Path.of("b.jpeg")));
        assertTrue(ImportFileType.isImage(Path.of("c.PNG")));
        assertTrue(ImportFileType.isImage(Path.of("d.webp")));
    }

    @Test
    void isImage_rejectsLeadingDotFileNames() {
        assertFalse(ImportFileType.isImage(Path.of(".secret.jpg")));
    }

    @Test
    void isImage_rejectsNonImages() {
        assertFalse(ImportFileType.isImage(Path.of("x.mp4")));
        assertFalse(ImportFileType.isImage(Path.of("readme.txt")));
    }

    @Test
    void isVideo_recognizesCommonExtensions() {
        assertTrue(ImportFileType.isVideo(Path.of("a.mp4")));
        assertTrue(ImportFileType.isVideo(Path.of("b.MOV")));
        assertTrue(ImportFileType.isVideo(Path.of("c.webm")));
        assertTrue(ImportFileType.isVideo(Path.of("d.m4v")));
        assertTrue(ImportFileType.isVideo(Path.of("e.mkv")));
    }

    @Test
    void isVideo_rejectsImages() {
        assertFalse(ImportFileType.isVideo(Path.of("x.png")));
    }
}
