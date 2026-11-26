/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.image;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * A mutable raster image with the operations image servlets need: crop, resize, rotate, flip and
 * encode. Implemented with Java2D and ImageIO (JPEG, PNG, GIF, BMP out of the box).
 */
public class Layer {

    private BufferedImage image;
    private Paint background = Color.WHITE;

    public Layer(BufferedImage image) {
        this.image = image;
    }

    public Layer(InputStream in) throws IOException {
        BufferedImage read = ImageIO.read(in);
        if (read == null) {
            throw new IOException("Unsupported image format");
        }
        this.image = read;
    }

    public Layer(int width, int height, Paint background) {
        this.image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        this.background = background;
        Graphics2D g = image.createGraphics();
        g.setPaint(background);
        g.fillRect(0, 0, width, height);
        g.dispose();
    }

    public int getWidth() {
        return image.getWidth();
    }

    public int getHeight() {
        return image.getHeight();
    }

    public BufferedImage getImage() {
        return image;
    }

    public Paint getBackground() {
        return background;
    }

    public void setBackground(Paint background) {
        this.background = background;
    }

    /** Crops to the rectangle (clamped to the image). */
    public void crop(Rectangle2D rect) {
        int x = clamp((int) Math.round(rect.getX()), 0, image.getWidth() - 1);
        int y = clamp((int) Math.round(rect.getY()), 0, image.getHeight() - 1);
        int w = clamp((int) Math.round(rect.getWidth()), 1, image.getWidth() - x);
        int h = clamp((int) Math.round(rect.getHeight()), 1, image.getHeight() - y);
        BufferedImage out = new BufferedImage(w, h, typeOf(image));
        Graphics2D g = out.createGraphics();
        g.drawImage(image, 0, 0, w, h, x, y, x + w, y + h, null);
        g.dispose();
        image = out;
    }

    /**
     * Resizes to {@code width} x {@code height}. A value of 0 keeps the aspect ratio.
     */
    public void resize(int width, int height) {
        int w = width;
        int h = height;
        if (w <= 0 && h <= 0) {
            return;
        }
        if (w <= 0) {
            w = Math.max(1, Math.round((float) image.getWidth() * h / image.getHeight()));
        }
        if (h <= 0) {
            h = Math.max(1, Math.round((float) image.getHeight() * w / image.getWidth()));
        }
        BufferedImage out = new BufferedImage(w, h, typeOf(image));
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(image, 0, 0, w, h, null);
        g.dispose();
        image = out;
    }

    /** Rotates clockwise by {@code degrees}; the canvas grows to fit and is filled with the background. */
    public void rotate(double degrees) {
        double rad = Math.toRadians(degrees);
        double sin = Math.abs(Math.sin(rad));
        double cos = Math.abs(Math.cos(rad));
        int w = image.getWidth();
        int h = image.getHeight();
        int nw = (int) Math.floor(w * cos + h * sin + 0.5);
        int nh = (int) Math.floor(h * cos + w * sin + 0.5);
        BufferedImage out = new BufferedImage(nw, nh, typeOf(image));
        Graphics2D g = out.createGraphics();
        g.setPaint(background);
        g.fillRect(0, 0, nw, nh);
        AffineTransform at = new AffineTransform();
        at.translate(nw / 2.0, nh / 2.0);
        at.rotate(rad);
        at.translate(-w / 2.0, -h / 2.0);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, at, null);
        g.dispose();
        image = out;
    }

    public void flipHorizontally() {
        transform(AffineTransform.getScaleInstance(-1, 1), -image.getWidth(), 0);
    }

    public void flipVertically() {
        transform(AffineTransform.getScaleInstance(1, -1), 0, -image.getHeight());
    }

    private void transform(AffineTransform base, double tx, double ty) {
        base.translate(tx, ty);
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), typeOf(image));
        Graphics2D g = out.createGraphics();
        g.drawImage(image, base, null);
        g.dispose();
        image = out;
    }

    /**
     * Encodes the image.
     *
     * @param mimeType e.g. {@code image/jpeg}, {@code image/png}
     * @param quality  0..1, used for lossy formats
     * @return {@code true} if a writer for the type exists and the image was written
     */
    public boolean write(String mimeType, double quality, OutputStream out) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType(mimeType);
        if (!writers.hasNext()) {
            return false;
        }
        ImageWriter writer = writers.next();
        BufferedImage toWrite = image;
        if ("image/jpeg".equals(mimeType) && image.getColorModel().hasAlpha()) {
            toWrite = flatten(image);
        }
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed() && quality > 0 && quality <= 1) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                if (param.getCompressionTypes() != null && param.getCompressionType() == null) {
                    param.setCompressionType(param.getCompressionTypes()[0]);
                }
                param.setCompressionQuality((float) quality);
            }
            writer.write(null, new IIOImage(toWrite, null, null), param);
        } finally {
            writer.dispose();
        }
        return true;
    }

    private BufferedImage flatten(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setPaint(background instanceof Color ? background : Color.WHITE);
        g.fillRect(0, 0, src.getWidth(), src.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static int typeOf(BufferedImage img) {
        return img.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
