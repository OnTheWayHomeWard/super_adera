package app.adera.registry.storage;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Turns an uploaded banner picture into one JPEG per size. Decoding and its safety checks are the logo's
 * ({@link LogoImageProcessor}: content sniffed, PNG/JPEG/GIF/BMP only, dimension limits); each size is scaled down (never
 * up) to fit its longest side, transparency is flattened onto white, and re-encoding drops metadata. JPEG because banners
 * are mostly photos, which are several times smaller than as PNG.
 */
public final class BannerImageProcessor {

	public record Variant(byte[] bytes, String contentType, int width, int height) {
	}

	static final float QUALITY = 0.85f;

	private BannerImageProcessor() {
	}

	/** @param sizes name -> longest side in pixels, in the order to produce them */
	public static Map<String, Variant> process(byte[] input, Map<String, Integer> sizes) {
		BufferedImage source = LogoImageProcessor.decode(input);
		Map<String, Variant> out = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> size : sizes.entrySet()) {
			BufferedImage fitted = LogoImageProcessor.fit(source, size.getValue());
			out.put(size.getKey(), new Variant(jpeg(onWhite(fitted)), "image/jpeg", fitted.getWidth(), fitted.getHeight()));
		}
		return out;
	}

	private static BufferedImage onWhite(BufferedImage image) {
		BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = rgb.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
		g.drawImage(image, 0, 0, null);
		g.dispose();
		return rgb;
	}

	private static byte[] jpeg(BufferedImage image) {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
			writer.setOutput(stream);
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(QUALITY);
			writer.write(null, new IIOImage(image, null, null), param);
		}
		catch (IOException e) {
			throw new IllegalStateException("could not encode banner image", e);
		}
		finally {
			writer.dispose();
		}
		return out.toByteArray();
	}

}
