package app.adera.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import app.adera.registry.auth.KratosClient;
import app.adera.registry.auth.Role;
import app.adera.registry.banner.BannerImages;
import app.adera.registry.storage.BannerImageProcessor;
import app.adera.registry.storage.InMemoryObjectStorage;
import app.adera.registry.storage.ObjectStorage;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Banner pictures: decoded like logos, stored as JPEG in three sizes, replaced cleanly, limited in size, audited. */
@SpringBootTest(properties = { "adera.registry.storage.type=memory", "adera.registry.banner.external-domains=adera.et,example.com",
		"adera.registry.banner.max-bytes=200000" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BannerImageTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	ObjectStorage storage;

	@MockitoBean
	KratosClient kratos;

	private static final UUID STAFF = UUID.randomUUID();

	static byte[] png(int w, int h, int type) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(w, h, type), "png", out);
		return out.toByteArray();
	}

	private UUID banner() throws Exception {
		String res = mvc.perform(post("/admin/api/v1/banners").header("X-Staff-Id", STAFF.toString()).header("X-Staff-Role", "ADMIN")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":{\"en\":\"Pic\"},\"placement\":\"HOME_HERO\",\"category\":\"PROMOTION\",\"actionType\":\"NONE\",\"startsAt\":\"2026-01-01T00:00:00Z\"}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(res, "$.id"));
	}

	private org.springframework.test.web.servlet.ResultActions upload(UUID id, Role role, MockMultipartFile file) throws Exception {
		var r = multipart("/admin/api/v1/banners/{id}/image", id);
		if (file != null) {
			r.file(file);
		}
		return mvc.perform(r.header("X-Staff-Id", STAFF.toString()).header("X-Staff-Role", role.name()));
	}

	private Map<String, byte[]> objects() {
		return ((InMemoryObjectStorage) storage).objects;
	}

	@Test
	void theProcessorMakesThreeJpegSizesAndNeverEnlarges() throws Exception {
		Map<String, BannerImageProcessor.Variant> v = BannerImageProcessor.process(png(2000, 800, BufferedImage.TYPE_INT_ARGB), BannerImages.SIZES);
		assertThat(v.keySet()).containsExactly("small", "medium", "large");
		assertThat(v.get("small").width()).isEqualTo(480);
		assertThat(v.get("small").height()).isEqualTo(192);
		assertThat(v.get("medium").width()).isEqualTo(960);
		assertThat(v.get("large").width()).isEqualTo(1600);
		assertThat(v.get("large").height()).isEqualTo(640);
		BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(v.get("large").bytes()));
		assertThat(decoded.getWidth()).isEqualTo(1600);
		assertThat(v.get("large").contentType()).isEqualTo("image/jpeg");
		assertThat(v.get("large").bytes()[0] & 0xFF).as("JPEG magic").isEqualTo(0xFF);
		Map<String, BannerImageProcessor.Variant> tiny = BannerImageProcessor.process(png(300, 600, BufferedImage.TYPE_INT_RGB), BannerImages.SIZES);
		assertThat(tiny.get("large").width()).isEqualTo(300);
		assertThat(tiny.get("small").height()).as("the longest side (here the height) is fitted").isEqualTo(480);
	}

	@Test
	void uploadStoresEverySizeReplacesTheOldFilesAndIsAudited() throws Exception {
		UUID id = banner();
		String first = upload(id, Role.ADMIN, new MockMultipartFile("file", "banner.png", "image/png", png(1800, 600, BufferedImage.TYPE_INT_RGB)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.images.small.width").value(480))
			.andExpect(jsonPath("$.images.small.height").value(160))
			.andExpect(jsonPath("$.images.large.width").value(1600))
			.andExpect(jsonPath("$.images.large.url").value(org.hamcrest.Matchers.startsWith("http://storage.test/banners/" + id + "/")))
			.andReturn().getResponse().getContentAsString();
		UUID img1 = UUID.fromString(JsonPath.read(first, "$.imageId"));
		for (String size : BannerImages.SIZES.keySet()) {
			assertThat(objects()).containsKey(ObjectStorage.bannerKey(id, img1, size));
		}
		String second = upload(id, Role.ADMIN, new MockMultipartFile("file", "x.bin", "application/octet-stream", png(640, 320, BufferedImage.TYPE_INT_ARGB)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.images.large.width").value(640)).andReturn().getResponse().getContentAsString();
		UUID img2 = UUID.fromString(JsonPath.read(second, "$.imageId"));
		assertThat(img2).isNotEqualTo(img1);
		assertThat(objects().keySet()).noneMatch(k -> k.contains(img1.toString())).anyMatch(k -> k.contains(img2.toString()));
		mvc.perform(get("/admin/api/v1/audit").param("target", id.toString()).param("action", "banner.image")
			.header("X-Staff-Id", STAFF.toString()).header("X-Staff-Role", "ADMIN"))
			.andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].meta.imageId").value(img2.toString()))
			.andExpect(jsonPath("$[0].meta.width").value(640));
		// with an image, it can be published
		mvc.perform(post("/admin/api/v1/banners/{id}/status", id).header("X-Staff-Id", STAFF.toString()).header("X-Staff-Role", "ADMIN")
			.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PUBLISHED\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.visible").value(true));
		// deleting the banner deletes its files
		mvc.perform(delete("/admin/api/v1/banners/{id}", id).header("X-Staff-Id", STAFF.toString()).header("X-Staff-Role", "ADMIN")).andExpect(status().isOk());
		assertThat(objects().keySet()).noneMatch(k -> k.contains(id.toString()));
	}

	@Test
	void badUploadsAreRefusedLikeLogos() throws Exception {
		UUID id = banner();
		upload(id, Role.ADMIN, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.file").exists());
		upload(id, Role.ADMIN, new MockMultipartFile("file", "a.png", "image/png", "<svg/>".getBytes())).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.file").value("not a supported image (use PNG, JPEG, GIF or BMP)"));
		upload(id, Role.ADMIN, new MockMultipartFile("file", "big.bmp", "image/bmp", new byte[200_001])).andExpect(status().isPayloadTooLarge())
			.andExpect(jsonPath("$.error").value("file_too_large"));
		upload(id, Role.VIEWER, new MockMultipartFile("file", "a.png", "image/png", png(10, 10, BufferedImage.TYPE_INT_RGB))).andExpect(status().isForbidden());
		upload(UUID.randomUUID(), Role.ADMIN, new MockMultipartFile("file", "a.png", "image/png", png(10, 10, BufferedImage.TYPE_INT_RGB)))
			.andExpect(status().isNotFound());
	}

}
