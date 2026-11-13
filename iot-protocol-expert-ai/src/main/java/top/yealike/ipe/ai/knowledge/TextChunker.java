package top.yealike.ipe.ai.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 协议文档分块器。
 *
 * <p>目标：保持章节语义、报文帧与命令字的完整单元；不做语义重写，不伪造页码。 纯函数实现，便于单测与离线评测。
 */
public final class TextChunker {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern CN_HEADING = Pattern.compile("^(第[一二三四五六七八九十百零\\d]+[章节条]\\s*.*)$");
    private static final Pattern NUM_HEADING = Pattern.compile("^(\\d+(\\.\\d+){0,3})\\.?\\s+(\\S.{0,60})$");
    private static final Pattern HEX_TOKEN = Pattern.compile("0x[0-9A-Fa-f]{1,4}");
    private static final Pattern WORD_TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9_.:/-]{1,}");
    private static final Pattern NUMBER_TOKEN = Pattern.compile("\\d{2,}");
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fff]{2,}");
    private static final Pattern WHITESPACE = Pattern.compile("[ \\t\\u00a0]+");

    private static final Set<String> STOP_WORDS = Set.of(
            "的是", "一个", "可以", "进行", "通过", "以及", "如果", "没有", "需要", "对于", "这个", "其中", "并且", "或者", "不会", "不是", "可能", "时候",
            "表示", "使用", "该字段", "如下");

    private TextChunker() {}

    public record Chunk(
            int chunkNo,
            String sectionPath,
            Integer pageNo,
            String content,
            String normalized,
            String keywords,
            int tokenCount) {}

    /**
     * @param pages 已按页抽取的文本；非 PDF 文档传单页即可，页码传 null 表示不可用
     */
    public static List<Chunk> split(List<Page> pages, int chunkSize, int overlap) {
        int size = Math.max(200, chunkSize);
        int overlapChars = Math.max(0, Math.min(overlap, size / 2));
        List<Chunk> chunks = new ArrayList<>();
        List<String> headingStack = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        String currentSection = "";
        Integer currentPage = null;
        int chunkNo = 1;

        for (Page page : pages) {
            for (String rawLine : page.text().split("\\R", -1)) {
                String line = rawLine.strip();
                Integer level = heading(line);
                if (level != null) {
                    if (!buffer.isEmpty()) {
                        chunkNo = flush(chunks, buffer, chunkNo, currentSection, currentPage, overlapChars, size);
                    }
                    updateHeadings(headingStack, line, level);
                    currentSection = String.join(" > ", headingStack);
                    currentPage = page.pageNo();
                    continue;
                }
                if (line.isEmpty()) {
                    if (!buffer.isEmpty()) {
                        chunkNo = flush(chunks, buffer, chunkNo, currentSection, currentPage, overlapChars, size);
                    }
                    continue;
                }
                if (buffer.length() + line.length() + 1 > size && !buffer.isEmpty()) {
                    chunkNo = flush(chunks, buffer, chunkNo, currentSection, currentPage, overlapChars, size);
                }
                if (line.length() > size) {
                    for (String piece : hardSplit(line, size)) {
                        buffer.append(piece).append('\n');
                        chunkNo = flush(chunks, buffer, chunkNo, currentSection, currentPage, overlapChars, size);
                    }
                } else {
                    buffer.append(line).append('\n');
                }
                if (currentPage == null) {
                    currentPage = page.pageNo();
                }
            }
        }
        if (!buffer.isEmpty()) {
            flush(chunks, buffer, chunkNo, currentSection, currentPage, overlapChars, size);
        }
        return chunks;
    }

    public record Page(Integer pageNo, String text) {}

    private static int flush(
            List<Chunk> chunks,
            StringBuilder buffer,
            int chunkNo,
            String section,
            Integer pageNo,
            int overlapChars,
            int chunkSize) {
        String content = buffer.toString().strip();
        buffer.setLength(0);
        if (content.length() < 8) {
            return chunkNo;
        }
        String normalized = normalize(content);
        chunks.add(new Chunk(
                chunkNo,
                section,
                pageNo,
                content,
                normalized,
                String.join(" ", keywords(content)),
                Math.max(1, content.length() / 2)));
        if (overlapChars > 0) {
            String tail =
                    content.length() > overlapChars ? content.substring(content.length() - overlapChars) : content;
            buffer.append(tail).append('\n');
            // 防止重叠内容被当成新块主体反复触发
            if (buffer.length() >= chunkSize) {
                buffer.setLength(0);
            }
        }
        return chunkNo + 1;
    }

    private static List<String> hardSplit(String line, int size) {
        List<String> parts = new ArrayList<>();
        int index = 0;
        while (index < line.length()) {
            int end = Math.min(line.length(), index + size);
            parts.add(line.substring(index, end));
            index = end;
        }
        return parts;
    }

    /** 返回标题级别；非标题返回 null。 */
    static Integer heading(String line) {
        if (line.isEmpty() || line.length() > 120) {
            return null;
        }
        Matcher markdown = MARKDOWN_HEADING.matcher(line);
        if (markdown.matches()) {
            return markdown.group(1).length();
        }
        if (CN_HEADING.matcher(line).matches()) {
            return 2;
        }
        Matcher numbered = NUM_HEADING.matcher(line);
        if (numbered.matches()) {
            return numbered.group(1).split("\\.").length + 1;
        }
        return null;
    }

    private static void updateHeadings(List<String> stack, String line, int level) {
        int depth = Math.max(1, Math.min(level, 6));
        while (stack.size() >= depth) {
            stack.removeLast();
        }
        while (stack.size() < depth - 1) {
            stack.add("");
        }
        stack.add(cleanHeading(line));
        stack.removeIf(String::isBlank);
    }

    private static String cleanHeading(String line) {
        return line.replaceAll("^#+\\s*", "").strip();
    }

    public static String normalize(String content) {
        return WHITESPACE
                .matcher(content)
                .replaceAll(" ")
                .toLowerCase(Locale.ROOT)
                .strip();
    }

    /** 关键词用于精确匹配排序：命令字、英文标识符、数字与中文二元组。 */
    public static List<String> keywords(String content) {
        Set<String> tokens = new LinkedHashSet<>();
        addMatches(tokens, HEX_TOKEN, content, false);
        addMatches(tokens, WORD_TOKEN, content, true);
        addMatches(tokens, NUMBER_TOKEN, content, false);
        Matcher cjk = CJK_RUN.matcher(content);
        while (cjk.find() && tokens.size() < 60) {
            String run = cjk.group();
            for (int i = 0; i + 2 <= run.length() && i < 160; i += 2) {
                String bigram = run.substring(i, i + 2);
                if (!STOP_WORDS.contains(bigram)) {
                    tokens.add(bigram);
                }
            }
        }
        return tokens.stream()
                .filter(token -> token.length() >= 2 && !STOP_WORDS.contains(token))
                .sorted((left, right) -> right.length() - left.length())
                .limit(24)
                .toList();
    }

    private static void addMatches(Set<String> tokens, Pattern pattern, String content, boolean lower) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find() && tokens.size() < 60) {
            String token = matcher.group();
            tokens.add(lower ? token.toLowerCase(Locale.ROOT) : token);
        }
    }
}
