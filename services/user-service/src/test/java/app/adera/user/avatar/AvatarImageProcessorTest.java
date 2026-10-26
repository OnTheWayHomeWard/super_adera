package app.adera.user.avatar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import app.adera.user.profile.ValidationFailedException;
import org.junit.jupiter.api.Test;

class AvatarImageProcessorTest {

	private static byte[] image(String format, int w, int h, int type) throws Exception {
		BufferedImage img = new BufferedImage(w, h, type);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.RED);
		g.fillRect(0, 0, w, h);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, format, out);
		return out.toByteArray();
	}

	private static BufferedImage decode(byte[] bytes) throws Exception {
		return ImageIO.read(new ByteArrayInputStream(bytes));
	}

	@Test
	void wideImageIsCroppedToASquareAndResized() throws Exception {
		var result = AvatarImageProcessor.process(image("png", 2000, 800, BufferedImage.TYPE_INT_RGB), 512);
		BufferedImage out = decode(result.bytes());
		assertThat(result.contentType()).isEqualTo("image/jpeg");
		assertThat(out.getWidth()).isEqualTo(512).isEqualTo(out.getHeight());
	}

	@Test
	void smallImagesAreNotUpscaled() throws Exception {
		BufferedImage out = decode(AvatarImageProcessor.process(image("png", 120, 120, BufferedImage.TYPE_INT_RGB), 512).bytes());
		assertThat(out.getWidth()).isEqualTo(120);
	}

	@Test
	void transparentPngBecomesWhiteNotBlack() throws Exception {
		BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB); // fully transparent
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		ImageIO.write(img, "png", png);
		BufferedImage out = decode(AvatarImageProcessor.process(png.toByteArray(), 64).bytes());
		Color c = new Color(out.getRGB(32, 32));
		assertThat(c.getRed()).isGreaterThan(240);
		assertThat(c.getGreen()).isGreaterThan(240);
		assertThat(c.getBlue()).isGreaterThan(240);
	}

	/** The point of re-encoding: a photo's GPS/camera metadata must not survive into storage. */
	@Test
	void metadataSuchAsExifGpsIsStripped() throws Exception {
		byte[] jpeg = image("jpeg", 300, 300, BufferedImage.TYPE_INT_RGB);
		byte[] payload = "Exif\0\0SECRET-GPS-9.0054N-38.7636E".getBytes(StandardCharsets.ISO_8859_1);
		int segmentLength = payload.length + 2;
		ByteArrayOutputStream withExif = new ByteArrayOutputStream();
		withExif.write(jpeg, 0, 2); // SOI
		withExif.write(new byte[] { (byte) 0xFF, (byte) 0xE1, (byte) (segmentLength >> 8), (byte) segmentLength });
		withExif.write(payload);
		withExif.write(jpeg, 2, jpeg.length - 2);
		assertThat(new String(withExif.toByteArray(), StandardCharsets.ISO_8859_1)).contains("SECRET-GPS");

		byte[] out = AvatarImageProcessor.process(withExif.toByteArray(), 256).bytes();
		assertThat(new String(out, StandardCharsets.ISO_8859_1)).doesNotContain("SECRET-GPS").doesNotContain("Exif");
	}

	@Test
	void nonImagesAreRejected() {
		assertThatThrownBy(() -> AvatarImageProcessor.process("<script>alert(1)</script>".getBytes(), 512))
			.isInstanceOf(ValidationFailedException.class)
			.hasMessageContaining("file");
		assertThatThrownBy(() -> AvatarImageProcessor.process(new byte[0], 512)).isInstanceOf(ValidationFailedException.class);
	}

	@Test
	void corruptImageIsRejectedNotA500() throws Exception {
		byte[] png = image("png", 100, 100, BufferedImage.TYPE_INT_RGB);
		byte[] truncated = java.util.Arrays.copyOf(png, png.length / 2);
		assertThatThrownBy(() -> AvatarImageProcessor.process(truncated, 512)).isInstanceOf(ValidationFailedException.class);
	}

	@Test
	void hugeDimensionsAreRejectedBeforeDecoding() throws Exception {
		byte[] sliver = image("png", 13_000, 1, BufferedImage.TYPE_INT_RGB); // tiny file, absurd width
		assertThatThrownBy(() -> AvatarImageProcessor.process(sliver, 512))
			.isInstanceOf(ValidationFailedException.class)
			.hasMessageContaining("too large");
	}

}
