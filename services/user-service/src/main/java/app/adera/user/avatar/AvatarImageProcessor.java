package app.adera.user.avatar;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Set;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import app.adera.user.profile.ValidationFailedException;

/**
 * Turns whatever the user uploaded into one small, safe JPEG: centre-cropped to a square, resized, and re-encoded.
 * Re-encoding drops all metadata (EXIF GPS position, camera, timestamps) and neutralises odd file tricks, and means
 * storage only ever holds one known format. Never trusts the filename or the declared content type: it decodes.
 */
public final class AvatarImageProcessor {

	public record Processed(byte[] bytes, String contentType) {
	}

	private static final Set<String> ACCEPTED = Set.of("jpeg", "png", "gif", "bmp");

	/** Guards against "decompression bombs": tiny files that decode to enormous bitmaps. */
	private static final long MAX_PIXELS = 40_000_000L;

	private static final int MAX_SIDE = 12_000;

	private static final float JPEG_QUALITY = 0.85f;

	private AvatarImageProcessor() {
	}

	public static Processed process(byte[] input, int sizePx) {
		BufferedImage source = decode(input);
		BufferedImage square = cropToSquare(source);
		BufferedImage resized = resize(square, Math.min(sizePx, square.getWidth()));
		return new Processed(encodeJpeg(resized), "image/jpeg");
	}

	private static BufferedImage decode(byte[] input) {
		try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
			if (!readers.hasNext()) {
				throw new ValidationFailedException("file", "not a supported image (use JPEG, PNG, GIF or BMP)");
			}
			ImageReader reader = readers.next();
			try {
				if (!ACCEPTED.contains(reader.getFormatName().toLowerCase())) {
					throw new ValidationFailedException("file", "not a supported image (use JPEG, PNG, GIF or BMP)");
				}
				reader.setInput(stream, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE || (long) width * height > MAX_PIXELS) {
					throw new ValidationFailedException("file", "image dimensions are too large");
				}
				return reader.read(0);
			}
			finally {
				reader.dispose();
			}
		}
		catch (IOException | RuntimeException e) {
			if (e instanceof ValidationFailedException v) {
				throw v;
			}
			throw new ValidationFailedException("file", "could not read the image, it may be corrupt");
		}
	}

	private static BufferedImage cropToSquare(BufferedImage image) {
		int side = Math.min(image.getWidth(), image.getHeight());
		int x = (image.getWidth() - side) / 2;
		int y = (image.getHeight() - side) / 2;
		return image.getSubimage(x, y, side, side);
	}

	/** Scales in halving steps then a final step: far better quality than one big jump. */
	private static BufferedImage resize(BufferedImage image, int target) {
		BufferedImage current = toRgb(image);
		int size = current.getWidth();
		while (size > target) {
			size = Math.max(target, size / 2);
			BufferedImage next = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = next.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(current, 0, 0, size, size, null);
			g.dispose();
			current = next;
		}
		return current;
	}

	/** JPEG has no alpha: flatten transparent PNGs onto white instead of letting them turn black. */
	private static BufferedImage toRgb(BufferedImage image) {
		BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = rgb.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
		g.drawImage(image, 0, 0, null);
		g.dispose();
		return rgb;
	}

	private static byte[] encodeJpeg(BufferedImage image) {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		try (ByteArrayOutputStream out = new ByteArrayOutputStream();
				MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(JPEG_QUALITY);
			writer.setOutput(stream);
			writer.write(null, new IIOImage(image, null, null), param); // null metadata: nothing carried over
			writer.dispose();
			stream.flush();
			return out.toByteArray();
		}
		catch (IOException e) {
			throw new IllegalStateException("could not encode avatar", e);
		}
	}

}
