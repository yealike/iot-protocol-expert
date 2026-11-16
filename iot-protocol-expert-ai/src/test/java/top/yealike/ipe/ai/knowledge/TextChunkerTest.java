package top.yealike.ipe.ai.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextChunkerTest {

    @Test
    void keepsSectionPathWhenHeadingChanges() {
        String text = """
                # 云快充 1.6 协议

                概述段落，描述协议适用范围。

                ## 3.2 停止充电

                0x3B 为设备主动停止充电。

                ### 3.2.1 停止原因

                停止原因取值 0x01 表示用户主动停止。
                """;
        List<TextChunker.Chunk> chunks = TextChunker.split(List.of(new TextChunker.Page(1, text)), 300, 40);
        assertTrue(chunks.size() >= 1);
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.sectionPath().contains("云快充 1.6 协议")));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.content().contains("0x3B")));
        assertEquals(1, chunks.get(0).pageNo());
    }

    @Test
    void splitsLongTextIntoBoundedChunks() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            builder.append("这是一段用于测试分块的协议说明文字，包含字段定义与取值范围。\n");
        }
        List<TextChunker.Chunk> chunks =
                TextChunker.split(List.of(new TextChunker.Page(null, builder.toString())), 400, 50);
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.content().length() <= 900));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.chunkNo() > 0));
    }

    @Test
    void keywordsPrioritizeCommandCodesAndEnglishTokens() {
        List<String> keywords = TextChunker.keywords("0x3B StopTransaction 停止原因 chargePointStatus");
        assertTrue(keywords.contains("0x3B"));
        assertTrue(keywords.contains("stoptransaction"));
        assertTrue(keywords.size() >= 3);
        assertTrue(keywords.contains("chargepointstatus"));
    }

    @Test
    void pageNumberIsUnavailableForPlainText() {
        List<TextChunker.Chunk> chunks =
                TextChunker.split(List.of(new TextChunker.Page(null, "纯文本协议说明，没有任何页码信息。")), 300, 0);
        assertEquals(1, chunks.size());
        assertNull(chunks.get(0).pageNo());
    }
}
