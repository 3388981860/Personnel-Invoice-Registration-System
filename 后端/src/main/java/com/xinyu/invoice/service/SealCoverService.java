package com.xinyu.invoice.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用印申请的封面图。
 *
 * 钉钉模板要求「用印文件照片」必填，但用户可能只传了附件，
 * 所以这里把附件首页（PDF）或附件信息（其它格式）渲染成一张封面图顶上去。
 */
@Service
public class SealCoverService {

    private static final Logger log = LoggerFactory.getLogger(SealCoverService.class);

    private static final Color BRAND = new Color(0x1B, 0x4F, 0x8C);
    private static final Color TEXT = new Color(0x1F, 0x29, 0x37);
    private static final Color MUTED = new Color(0x6B, 0x72, 0x80);
    private static final Color LINE = new Color(0xE0, 0xE5, 0xEC);

    private final Path dir;

    public SealCoverService() {
        System.setProperty("java.awt.headless", "true");
        this.dir = Path.of(System.getProperty("user.dir"), "数据", "临时", "用印附件");
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            log.warn("封面缓存目录创建失败：{}", e.getMessage());
        }
    }

    /** 上传附件时把内容存下来，提交时才有素材生成封面 */
    public void saveAttachment(String mediaId, String filename, byte[] data) {
        try {
            String key = safe(mediaId);
            Files.write(dir.resolve(key + ".bin"), data);
            Files.writeString(dir.resolve(key + ".name"), filename == null ? "" : filename);
        } catch (Exception e) {
            log.warn("附件缓存失败 {}：{}", mediaId, e.getMessage());
        }
    }

    /** 生成封面 PNG；没有素材返回 null */
    public byte[] cover(String mediaId, Map<String, String> meta) {
        try {
            String key = safe(mediaId);
            Path bin = dir.resolve(key + ".bin");
            if (!Files.exists(bin)) {
                return null;
            }
            byte[] data = Files.readAllBytes(bin);
            String name = "";
            Path namePath = dir.resolve(key + ".name");
            if (Files.exists(namePath)) {
                name = Files.readString(namePath).trim();
            }

            BufferedImage img = isPdf(data) ? fromPdf(data) : null;
            if (img == null) {
                img = card(name, meta);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            log.warn("生成封面失败 {}：{}", mediaId, e.getMessage());
            return null;
        }
    }

    private static boolean isPdf(byte[] data) {
        return data.length > 4 && data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F';
    }

    /** PDF：渲染第一页当封面 */
    private BufferedImage fromPdf(byte[] data) {
        try (PDDocument doc = PDDocument.load(data)) {
            if (doc.getNumberOfPages() == 0) {
                return null;
            }
            BufferedImage page = new PDFRenderer(doc).renderImageWithDPI(0, 130);
            return scaleToWidth(page, 1000);
        } catch (Exception e) {
            log.warn("PDF 渲染失败：{}", e.getMessage());
            return null;
        }
    }

    private static BufferedImage scaleToWidth(BufferedImage src, int targetW) {
        if (src.getWidth() <= targetW) {
            return src;
        }
        int h = (int) Math.round(src.getHeight() * (targetW / (double) src.getWidth()));
        BufferedImage out = new BufferedImage(targetW, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, targetW, h, null);
        g.dispose();
        return out;
    }

    /** 其它格式：画一张封面卡片 */
    private BufferedImage card(String filename, Map<String, String> meta) {
        int w = 1000;
        int h = 1400;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);

        // 顶部色带
        g.setColor(BRAND);
        g.fillRect(0, 0, w, 200);
        g.setColor(Color.WHITE);
        g.setFont(font(Font.BOLD, 46));
        g.drawString("用 印 文 件", 70, 105);
        g.setFont(font(Font.PLAIN, 24));
        g.drawString("人数行政平台 · 自动生成封面", 70, 155);

        int y = 290;
        g.setColor(TEXT);
        g.setFont(font(Font.BOLD, 34));
        List<String> lines = wrap(meta.getOrDefault("doc_name", "（未命名文件）"), g, w - 140, 34);
        for (String line : lines) {
            g.drawString(line, 70, y);
            y += 48;
        }

        y += 40;
        g.setColor(LINE);
        g.drawLine(70, y, w - 70, y);
        y += 60;

        String[][] rows = {
                {"经办人", meta.getOrDefault("person", "")},
                {"日期", meta.getOrDefault("date", "")},
                {"文件类别", meta.getOrDefault("category", "")},
                {"印章", meta.getOrDefault("seal", "")},
                {"文件份数", meta.getOrDefault("copies", "")},
                {"附件", filename == null || filename.isBlank() ? "（未命名）" : filename}
        };
        for (String[] row : rows) {
            g.setColor(MUTED);
            g.setFont(font(Font.PLAIN, 26));
            g.drawString(row[0], 70, y);
            g.setColor(TEXT);
            g.setFont(font(Font.PLAIN, 28));
            g.drawString(row[1], 260, y);
            y += 62;
        }

        g.setColor(LINE);
        g.drawLine(70, h - 170, w - 70, h - 170);
        g.setColor(MUTED);
        g.setFont(font(Font.PLAIN, 22));
        g.drawString("本图由系统根据附件自动生成，用于钉钉审批留痕。", 70, h - 110);
        g.dispose();
        return img;
    }

    private static List<String> wrap(String text, Graphics2D g, int maxW, int size) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        FontMetrics fm = g.getFontMetrics();
        for (char c : text.toCharArray()) {
            if (fm.stringWidth(cur.toString() + c) > maxW) {
                out.add(cur.toString());
                cur = new StringBuilder();
            }
            cur.append(c);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out.isEmpty() ? List.of("") : out;
    }

    private static final Map<String, String> FONT_CACHE = new HashMap<>();

    private static Font font(int style, int size) {
        String family = "Noto Sans CJK SC";
        Font f = new Font(family, style, size);
        if (!f.getFamily().equalsIgnoreCase(family) || f.canDisplay('用') == false) {
            f = new Font(Font.SANS_SERIF, style, size);
        }
        if (!f.canDisplay('用')) {
            f = new Font("Noto Serif CJK SC", style, size);
        }
        return f;
    }

    private static String safe(String id) {
        return id == null ? "unknown" : id.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
