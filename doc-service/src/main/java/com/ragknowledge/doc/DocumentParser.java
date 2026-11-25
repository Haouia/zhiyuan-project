package com.ragknowledge.doc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.IBody;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;

final class DocumentParser {
    static final String TXT = "text/plain";
    static final String PDF = "application/pdf";
    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final int MAX_TEXT = 10 * 1024 * 1024;
    private static final int MAX_POSITIONS = 100_000;
    private static final int MAX_PAGES = 2_000;

    static {
        ZipSecureFile.setMaxEntrySize(24L * 1024 * 1024);
        ZipSecureFile.setMaxFileCount(1_000);
    }

    Parsed parse(String type, byte[] bytes) {
        try {
            return switch (type) {
                case TXT -> text(bytes);
                case DOCX -> docx(bytes);
                case PDF -> pdf(bytes);
                default -> throw new Failed("INVALID_DOCUMENT");
            };
        } catch (Failed error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new Failed("INVALID_DOCUMENT");
        }
    }

    private Parsed text(byte[] bytes) {
        var output = new Extracted("line");
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!value.isEmpty() && value.charAt(0) == '\uFEFF') {
            value = value.substring(1);
        }
        int number = 0;
        try (var lines = value.lines()) {
            var iterator = lines.iterator();
            while (iterator.hasNext()) {
                output.add(++number, iterator.next());
            }
        }
        return output.finish();
    }

    private Parsed docx(byte[] bytes) throws IOException {
        var output = new Extracted("paragraph");
        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            readParagraphs(document, output, 0, 0);
        }
        return output.finish();
    }

    private int readParagraphs(IBody body, Extracted output, int number, int depth) {
        if (depth > 30) {
            throw new Failed("DOCUMENT_TOO_COMPLEX");
        }
        for (IBodyElement element : body.getBodyElements()) {
            if (element instanceof XWPFParagraph paragraph) {
                output.add(++number, paragraph.getText());
            } else if (element instanceof XWPFTable table) {
                for (var row : table.getRows()) {
                    for (var cell : row.getTableCells()) {
                        number = readParagraphs(cell, output, number, depth + 1);
                    }
                }
            }
        }
        return number;
    }

    private Parsed pdf(byte[] bytes) throws IOException {
        var extracted = new Extracted("page");
        var pageText = new StringBuilder();
        try (var document = Loader.loadPDF(bytes)) {
            if (!document.getCurrentAccessPermission().canExtractContent()) {
                throw new Failed("INVALID_DOCUMENT");
            }
            // 页数在抽取前先卡住：空白页不占位置配额，却会逐页消耗解析时间和内存
            if (document.getNumberOfPages() > MAX_PAGES) {
                throw new Failed("DOCUMENT_TOO_COMPLEX");
            }
            var stripper = new PDFTextStripper() {
                @Override
                protected void endPage(PDPage page) {
                    extracted.add(getCurrentPageNo(), pageText.toString());
                    pageText.setLength(0);
                }
            };
            stripper.setSortByPosition(true);
            stripper.writeText(document, new Writer() {
                @Override
                public void write(char[] buffer, int start, int length) {
                    if (length > MAX_TEXT - pageText.length() - extracted.text.length()) {
                        throw new Failed("DOCUMENT_TOO_COMPLEX");
                    }
                    pageText.append(buffer, start, length);
                }

                @Override
                public void flush() {}

                @Override
                public void close() {}
            });
        }
        return extracted.finish();
    }

    private static final class Extracted {
        private final String unit;
        private final StringBuilder text = new StringBuilder();
        private final List<Position> positions = new ArrayList<>();

        private Extracted(String unit) {
            this.unit = unit;
        }

        private void add(int number, String value) {
            value = value.strip();
            if (value.isEmpty()) {
                return;
            }
            int separator = text.isEmpty() ? 0 : 1;
            if (positions.size() == MAX_POSITIONS || value.length() > MAX_TEXT - text.length() - separator) {
                throw new Failed("DOCUMENT_TOO_COMPLEX");
            }
            if (separator != 0) {
                text.append('\n');
            }
            int start = text.length();
            text.append(value);
            positions.add(new Position(number, start, text.length()));
        }

        private Parsed finish() {
            if (positions.isEmpty()) {
                throw new Failed("NO_EXTRACTABLE_TEXT");
            }
            return new Parsed(text.toString(), new Locations(unit, positions));
        }
    }

    record Parsed(String text, Locations locations) {}

    record Locations(String unit, List<Position> positions) {}

    record Position(int number, int start, int end) {}

    static class Failed extends RuntimeException {
        Failed(String code) {
            super(code);
        }
    }
}
