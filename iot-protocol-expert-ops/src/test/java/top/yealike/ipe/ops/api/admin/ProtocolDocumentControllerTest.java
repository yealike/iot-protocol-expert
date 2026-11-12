package top.yealike.ipe.ops.api.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

class ProtocolDocumentControllerTest {
    @TempDir Path root;

    @Test
    void listReturnsEnglishNameAndReferencedDocumentCannotBeArchived() {
        var jdbc = mock(JdbcTemplate.class);
        var controller = new ProtocolDocumentController(jdbc, root.toString());
        controller.list(null);
        verify(jdbc).queryForList(contains("protocol_name_en as \"protocolNameEn\""), eq(""), eq(""));
        when(jdbc.queryForList(contains("for update"), eq(10L))).thenReturn(java.util.List.of(java.util.Map.of("id", 10L)));
        when(jdbc.queryForObject(contains("count(*)"), eq(Integer.class), eq(10L))).thenReturn(1);
        assertThrows(IllegalArgumentException.class, () -> controller.archive(10L));
        verify(jdbc, never()).update(anyString(), eq(10L));
    }

    @Test
    void viewIsInlineAndDownloadRemainsAttachment() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var key = "12345678-1234-1234-1234-123456789abc";
        Files.writeString(root.resolve(key), "hello");
        when(jdbc.queryForList(contains("storage_key"), eq(10L))).thenReturn(java.util.List.of(
                java.util.Map.of("storage_key", key, "original_filename", "test.md", "content_type", "text/plain")));
        var controller = new ProtocolDocumentController(jdbc, root.toString());
        var view = controller.view(10L);
        assertTrue(view.getHeaders().getContentDisposition().isInline());
        assertEquals(java.nio.charset.StandardCharsets.UTF_8, view.getHeaders().getContentType().getCharset());
        assertEquals("nosniff", view.getHeaders().getFirst("X-Content-Type-Options"));
        assertTrue(controller.download(10L).getHeaders().getContentDisposition().isAttachment());
    }

    @Test
    void rejectsDisguisedPdfAndRemovesTemporaryFile() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var controller = new ProtocolDocumentController(jdbc, root.toString());
        var file = new MockMultipartFile("file", "test.pdf", "application/pdf", "not a pdf".getBytes());
        assertThrows(IllegalArgumentException.class, () -> controller.upload("云快充", "YKC", "1.6", "规范", file, () -> "admin"));
        try (var files = Files.list(root)) { assertEquals(0, files.count()); }
        verifyNoInteractions(jdbc);
    }

    @Test
    void refusesBlankProtocolBeforeWriting() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var controller = new ProtocolDocumentController(jdbc, root.toString());
        var file = new MockMultipartFile("file", "test.txt", "text/plain", "content".getBytes());
        assertThrows(IllegalArgumentException.class, () -> controller.upload(" ", "YKC", "1", "test", file, () -> "admin"));
        try (var files = Files.list(root)) { assertEquals(0, files.count()); }
        verifyNoInteractions(jdbc);
    }

    @Test
    void databaseFailureDoesNotLeaveUploadedFile() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        doThrow(new IllegalStateException("database unavailable")).when(jdbc).queryForObject(anyString(), eq(Long.class),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        var controller = new ProtocolDocumentController(jdbc, root.toString());
        var file = new MockMultipartFile("file", "test.txt", "text/plain", "content".getBytes());
        assertThrows(IllegalStateException.class, () -> controller.upload("云快充", "YKC", "1.6", "规范", file, () -> "admin"));
        try (var files = Files.list(root)) { assertEquals(0, files.count()); }
    }
}

