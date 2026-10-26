package app.adera.registry.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import app.adera.registry.common.Errors;
import org.junit.jupiter.api.Test;

class LogoImageProcessorTest {

	private static byte[] image(String format, int w, int h, int type, Color fill) throws Exception {
		BufferedImage img = new BufferedImage(w, h, type);
		Graphics2D g = img.createGraphics();
		if (fill != null) {
			g.setColor(fill);
			g.fillRect(0, 0, w, h);
		}
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, format, out);
		return out.toByteArray();
	}

	@Test
	void largeImagesShrinkKeepingTheAspectRatio() throws Exception {
		var result = LogoImageProcessor.process(image("png", 2000, 1000, BufferedImage.TYPE_INT_RGB, Color.BLUE), 512);
		assertThat(result.contentType()).isEqualTo("image/png");
		assertThat(result.width()).isEqualTo(512);
		assertThat(result.height()).isEqualTo(256);
	}

	@Test
	void smallImagesAreNotUpscaled() throws Exception {
		var result = LogoImageProcessor.process(image("jpeg", 100, 60, BufferedImage.TYPE_INT_RGB, Color.RED), 512);
		assertThat(result.width()).isEqualTo(100);
		assertThat(result.height()).isEqualTo(60);
	}

	@Test
	void transparencyIsKept() throws Exception {
		BufferedImage out = ImageIO.read(new ByteArrayInputStream(
				LogoImageProcessor.process(image("png", 64, 64, BufferedImage.TYPE_INT_ARGB, null), 512).bytes()));
		assertThat(out.getColorModel().hasAlpha()).isTrue();
		assertThat(new Color(out.getRGB(10, 10), true).getAlpha()).isZero();
	}

	@Test
	void nonImagesAndCorruptFilesAreRejected() throws Exception {
		assertThatThrownBy(() -> LogoImageProcessor.process("<svg onload=alert(1)>".getBytes(), 512)).isInstanceOf(Errors.ValidationFailed.class);
		byte[] png = image("png", 100, 100, BufferedImage.TYPE_INT_RGB, Color.RED);
		byte[] half = java.util.Arrays.copyOf(png, png.length / 2);
		assertThatThrownBy(() -> LogoImageProcessor.process(half, 512)).isInstanceOf(Errors.ValidationFailed.class);
	}

	@Test
	void aWebpFileIsRefusedWithAMessageThatNamesTheAcceptedFormats() {
		// the header of a WebP file (RIFF....WEBPVP8 ): not decodable by the JDK, and not one of the accepted formats
		byte[] webp = new byte[64];
		System.arraycopy("RIFF".getBytes(), 0, webp, 0, 4);
		System.arraycopy("WEBPVP8 ".getBytes(), 0, webp, 8, 8);
		assertThatThrownBy(() -> LogoImageProcessor.process(webp, 512)).isInstanceOfSatisfying(Errors.ValidationFailed.class,
				e -> assertThat(e.fields().get("file")).isEqualTo("not a supported image (use PNG, JPEG, GIF or BMP)"));
	}

	@Test
	void gifAndBmpLogosAreAccepted() throws Exception {
		for (String format : new String[] { "gif", "bmp" }) {
			assertThat(LogoImageProcessor.process(image(format, 120, 80, BufferedImage.TYPE_INT_RGB, Color.BLUE), 512).contentType()).isEqualTo("image/png");
		}
	}

	@Test
	void absurdDimensionsAreRejectedBeforeDecoding() throws Exception {
		byte[] sliver = image("png", 13_000, 1, BufferedImage.TYPE_INT_RGB, Color.RED);
		assertThatThrownBy(() -> LogoImageProcessor.process(sliver, 512)).isInstanceOf(Errors.ValidationFailed.class).hasMessageContaining("too large");
	}

}
