package com.monada.evaluation.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.monada.evaluation.book.BookInputException.Reason;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifiedBookSourceTest {
    @TempDir
    Path dir;

    private static BookInputException rejectBytes(byte[] body) {
        return assertThrows(BookInputException.class,
                () -> VerifiedBookSource.ofBytes("c", "1", "0".repeat(64), body));
    }

    private static BookInputException reject(String body) {
        return rejectBytes(body.getBytes(StandardCharsets.UTF_8));
    }

    private Path manifest(String body, String extra) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Path manifest = dir.resolve("manifest.properties");
        Files.writeString(manifest, "corpus=c\ncorpusVersion=1\nnormalized.sizeBytes=" + bytes.length
                + "\nnormalized.sha256=" + VerifiedBookSource.sha256(bytes) + "\n" + extra);
        return manifest;
    }

    @Test
    void acceptsAVerifiedBodyAndRecordsDigests() throws Exception {
        String body = "Café text.\n";
        Path manifest = manifest(body, "");
        Path file = dir.resolve("body.txt");
        Files.writeString(file, body);

        VerifiedBookSource source = VerifiedBookSource.load(manifest, file);
        assertEquals(body, source.text());
        assertEquals(12, source.sizeBytes());
        assertEquals(VerifiedBookSource.sha256(Files.readAllBytes(manifest)), source.manifestSha256());
    }

    @Test
    void rejectsABodyThatDoesNotMatchTheManifestPins() throws Exception {
        Path manifest = manifest("Expected text.\n", "");
        Path file = dir.resolve("body.txt");
        Files.writeString(file, "Different text.\n");
        BookInputException e = assertThrows(BookInputException.class, () -> VerifiedBookSource.load(manifest, file));
        assertEquals(Reason.INVALID_INPUT, e.reason());
        assertTrue(e.getMessage().contains("--write-normalized"), e.getMessage());
    }

    @Test
    void rejectsManifestsWithMissingOrMalformedPins() throws Exception {
        Path file = dir.resolve("body.txt");
        Files.writeString(file, "Text.\n");
        Path manifest = dir.resolve("manifest.properties");
        for (String content : new String[] {
                "corpus=c\ncorpusVersion=1\nnormalized.sizeBytes=6\n",
                "corpus=c\ncorpusVersion=1\nnormalized.sizeBytes=six\nnormalized.sha256=" + "a".repeat(64) + "\n",
                "corpus=c\ncorpusVersion=1\nnormalized.sizeBytes=6\nnormalized.sha256=ABC\n",
                "corpus=Bad Corpus\ncorpusVersion=1\nnormalized.sizeBytes=6\nnormalized.sha256="
                        + VerifiedBookSource.sha256("Text.\n".getBytes(StandardCharsets.UTF_8)) + "\n",
                "corpus=c\\u00zz\n"}) {
            Files.writeString(manifest, content);
            BookInputException e = assertThrows(BookInputException.class,
                    () -> VerifiedBookSource.load(manifest, file), content);
            assertEquals(Reason.BAD_MANIFEST, e.reason(), content);
        }
    }

    @Test
    void rejectsInvalidUtf8WithItsByteOffset() {
        BookInputException e = rejectBytes(new byte[] {'a', 'b', (byte) 0xC3, '(', '\n'});
        assertEquals(Reason.INVALID_INPUT, e.reason());
        assertTrue(e.getMessage().contains("byte offset 2"), e.getMessage());
    }

    @Test
    void rejectsMalformedBodies() {
        assertTrue(rejectBytes(new byte[0]).getMessage().contains("empty"));
        assertTrue(reject("﻿text\n").getMessage().contains("BOM"));
        assertTrue(reject("line\r\nline\n").getMessage().contains("CR"));
        assertTrue(reject("bell\u0007\n").getMessage().contains("U+0007"));
        assertTrue(reject("no final newline").getMessage().contains("LF"));
        assertTrue(reject(" \n\t\n \n").getMessage().contains("whitespace"));
        assertTrue(reject("Café\n").getMessage().contains("NFC"));
    }
}
