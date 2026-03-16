package com.example.arrows.service;

import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;

@Service
public class SvgRasterizer {

    private static final int BASE_RENDER_SIZE = 600;

    /**
     * Converts an SVG string into a boolean grid mask of size gridSize x gridSize.
     * A cell is filled if the average pixel darkness in that tile exceeds the threshold.
     *
     * @param svgContent the SVG as a string
     * @param gridSize   the N for the NxN grid
     * @param threshold  alpha threshold (0-255), higher = denser fill
     * @return boolean[row][col] grid mask
     */
    public boolean[][] rasterize(String svgContent, int gridSize, int threshold) throws Exception {
        // Validate SVG doesn't contain embedded raster images or text
        String lower = svgContent.toLowerCase();
        if (lower.contains("<image") || lower.contains("xlink:href")) {
            throw new IllegalArgumentException(
                "SVG contains embedded images. Only path/shape SVGs are supported.");
        }
        if (lower.contains("<text") || lower.contains("<tspan")) {
            throw new IllegalArgumentException(
                "SVG contains text elements. Only path/shape SVGs are supported.");
        }

        // Render SVG to BufferedImage using Batik
        // Scale render size with grid to keep at least ~15px per cell for accuracy
        int renderSize = Math.max(BASE_RENDER_SIZE, gridSize * 15);
        BufferedImage image = renderSvgToImage(svgContent, renderSize);

        // Sample the image on an NxN grid
        boolean[][] mask = new boolean[gridSize][gridSize];
        int tileW = image.getWidth() / gridSize;
        int tileH = image.getHeight() / gridSize;

        for (int r = 0; r < gridSize; r++) {
            for (int c = 0; c < gridSize; c++) {
                int startX = c * tileW;
                int startY = r * tileH;
                double avgDarkness = computeAverageDarkness(image, startX, startY, tileW, tileH);
                mask[r][c] = avgDarkness > threshold;
            }
        }

        return mask;
    }

    private BufferedImage renderSvgToImage(String svgContent, int renderSize) throws Exception {
        PNGTranscoder transcoder = new PNGTranscoder();
        transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) renderSize);
        transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) renderSize);

        TranscoderInput input = new TranscoderInput(new StringReader(svgContent));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TranscoderOutput output = new TranscoderOutput(baos);

        transcoder.transcode(input, output);

        return ImageIO.read(new ByteArrayInputStream(baos.toByteArray()));
    }

    /**
     * Computes average "darkness" of a tile region.
     * For each pixel, we consider it "dark" if it has significant alpha and low brightness.
     * Returns a value 0-255.
     */
    private double computeAverageDarkness(BufferedImage image, int startX, int startY,
                                           int tileW, int tileH) {
        long totalDarkness = 0;
        int count = 0;

        int maxX = Math.min(startX + tileW, image.getWidth());
        int maxY = Math.min(startY + tileH, image.getHeight());

        for (int y = startY; y < maxY; y++) {
            for (int x = startX; x < maxX; x++) {
                int rgba = image.getRGB(x, y);
                int alpha = (rgba >> 24) & 0xFF;
                int red = (rgba >> 16) & 0xFF;
                int green = (rgba >> 8) & 0xFF;
                int blue = rgba & 0xFF;

                // Compute perceived brightness
                double brightness = 0.299 * red + 0.587 * green + 0.114 * blue;
                // Dark pixel = high alpha and low brightness
                double darkness = alpha * (255 - brightness) / 255.0;
                totalDarkness += (long) darkness;
                count++;
            }
        }

        return count > 0 ? (double) totalDarkness / count : 0;
    }
}
