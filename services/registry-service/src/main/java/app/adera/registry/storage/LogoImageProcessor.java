package app.adera.registry.storage;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import app.adera.registry.common.Errors;

/**
 * Normalises an uploaded logo: decodes it (never trusting the filename or declared type), scales it down to fit within
 * a square of {@code maxPx} while keeping the aspect ratio and any transparency, and re-encodes as PNG. Re-encoding
 * drops metadata and odd file tricks; dimension limits guard against decompression bombs.
 */
public final class LogoImageProcessor {

	public record Processed(byte[] bytes, String contentType, int width, int height) {
	}

	private static final Set<String> ACCEPTED = Set.of("jpeg", "png", "gif", "bmp");
	private static final long MAX_PIXELS = 40_000_000L;
	private static final int MAX_SIDE = 12_000;

	private LogoImageProcessor() {
	}

	public static Processed process(byte[] input, int maxPx) {
		BufferedImage source = decode(input);
		BufferedImage fitted = fit(source, maxPx);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			ImageIO.write(fitted, "png", out);
		}
		catch (IOException e) {
			throw new IllegalStateException("could not encode logo", e);
		}
		return new Processed(out.toByteArray(), "image/png", fitted.getWidth(), fitted.getHeight());
	}

	private static BufferedImage decode(byte[] input) {
		try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
			if (!readers.hasNext()) {
				throw unsupported();
			}
			ImageReader reader = readers.next();
			try {
				if (!ACCEPTED.contains(reader.getFormatName().toLowerCase())) {
					throw unsupported();
				}
				reader.setInput(stream, true, true);
				int w = reader.getWidth(0);
				int h = reader.getHeight(0);
				if (w < 1 || h < 1 || w > MAX_SIDE || h > MAX_SIDE || (long) w * h > MAX_PIXELS) {
					throw new Errors.ValidationFailed("file", "image dimensions are too large");
				}
				return reader.read(0);
			}
			finally {
				reader.dispose();
			}
		}
		catch (IOException | RuntimeException e) {
			if (e instanceof Errors.ValidationFailed v) {
				throw v;
			}
			throw new Errors.ValidationFailed("file", "could not read the image, it may be corrupt");
		}
	}

	private static Errors.ValidationFailed unsupported() {
		return new Errors.ValidationFailed("file", "not a supported image (use PNG, JPEG, GIF or BMP)");
	}

	private static BufferedImage fit(BufferedImage image, int maxPx) {
		int w = image.getWidth();
		int h = image.getHeight();
		double scale = Math.min(1.0, (double) maxPx / Math.max(w, h));
		int tw = Math.max(1, (int) Math.round(w * scale));
		int th = Math.max(1, (int) Math.round(h * scale));
		BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(image, 0, 0, tw, th, null);
		g.dispose();
		return out;
	}

}
