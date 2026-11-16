package top.yealike.ipe.ops.api.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import top.yealike.ipe.ops.mapper.ProtocolDocumentMapper;

class ProtocolDocumentControllerTest {
    @TempDir
    Path root;

    @Test
    void listReturnsEnglishNameAndReferencedDocumentCannotBeArchived() {
        var documents = mock(ProtocolDocumentMapper.class);
        var controller = new ProtocolDocumentController(documents, root.toString());
        controller.list(null);
        verify(documents).selectActive("");
        when(documents.selectActiveIdForUpdate(10L)).thenReturn(10L);
        when(documents.countProductReferences(10L)).thenReturn(1L);
        assertThrows(IllegalArgumentException.class, () -> controller.archive(10L));
        verify(documents, never()).archive(anyLong());
    }

    @Test
    void viewIsInlineAndDownloadRemainsAttachment() throws Exception {
        var documents = mock(ProtocolDocumentMapper.class);
        var key = "12345678-1234-1234-1234-123456789abc";
        Files.writeString(root.resolve(key), "hello");
        when(documents.selectFileInfo(10L))
                .thenReturn(new ProtocolDocumentMapper.FileInfo(key, "test.md", "text/plain"));
        var controller = new ProtocolDocumentController(documents, root.toString());
        var view = controller.view(10L);
        assertTrue(view.getHeaders().getContentDisposition().isInline());
        assertEquals(
                java.nio.charset.StandardCharsets.UTF_8,
                view.getHeaders().getContentType().getCharset());
        assertEquals("nosniff", view.getHeaders().getFirst("X-Content-Type-Options"));
        assertTrue(controller.download(10L).getHeaders().getContentDisposition().isAttachment());
    }

    @Test
    void rejectsDisguisedPdfAndRemovesTemporaryFile() throws Exception {
        var documents = mock(ProtocolDocumentMapper.class);
        var controller = new ProtocolDocumentController(documents, root.toString());
        var file = new MockMultipartFile("file", "test.pdf", "application/pdf", "not a pdf".getBytes());
        assertThrows(
                IllegalArgumentException.class,
                () -> controller.upload("云快充", "YKC", "1.6", "规范", file, () -> "admin"));
        try (var files = Files.list(root)) {
            assertEquals(0, files.count());
        }
        verifyNoInteractions(documents);
    }

    @Test
    void refusesBlankProtocolBeforeWriting() throws Exception {
        var documents = mock(ProtocolDocumentMapper.class);
        var controller = new ProtocolDocumentController(documents, root.toString());
        var file = new MockMultipartFile("file", "test.txt", "text/plain", "content".getBytes());
        assertThrows(
                IllegalArgumentException.class, () -> controller.upload(" ", "YKC", "1", "test", file, () -> "admin"));
        try (var files = Files.list(root)) {
            assertEquals(0, files.count());
        }
        verifyNoInteractions(documents);
    }

    @Test
    void databaseFailureDoesNotLeaveUploadedFile() throws Exception {
        var documents = mock(ProtocolDocumentMapper.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(documents)
                .insertDocument(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        anyString(),
                        anyString());
        var controller = new ProtocolDocumentController(documents, root.toString());
        var file = new MockMultipartFile("file", "test.txt", "text/plain", "content".getBytes());
        assertThrows(
                IllegalStateException.class, () -> controller.upload("云快充", "YKC", "1.6", "规范", file, () -> "admin"));
        try (var files = Files.list(root)) {
            assertEquals(0, files.count());
        }
    }
}
