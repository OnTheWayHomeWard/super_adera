package app.adera.user.avatar;

import java.net.URI;
import java.time.Duration;

import app.adera.user.config.UserProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * RustFS (S3-compatible) storage. Two endpoints on purpose: this service reaches RustFS by its internal address, but
 * signed links given to phones must carry the address phones can reach, because the host is part of the signature.
 */
@Component
@ConditionalOnProperty(prefix = "adera.user.storage", name = "type", havingValue = "s3", matchIfMissing = true)
public class S3AvatarStorage implements AvatarStorage, ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(S3AvatarStorage.class);

	private final S3Client client;
	private final S3Presigner presigner;
	private final String bucket;
	private volatile boolean bucketReady;

	public S3AvatarStorage(UserProperties props) {
		UserProperties.Storage s = props.storage();
		if (s.accessKey().isBlank() || s.secretKey().isBlank()) {
			throw new IllegalStateException("STORAGE_ACCESS_KEY / STORAGE_SECRET_KEY are not set");
		}
		this.bucket = s.bucket();
		StaticCredentialsProvider credentials = StaticCredentialsProvider
			.create(AwsBasicCredentials.create(s.accessKey(), s.secretKey()));
		Region region = Region.of(s.region());
		S3Configuration pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
		this.client = S3Client.builder()
			.endpointOverride(URI.create(s.endpoint()))
			.region(region)
			.credentialsProvider(credentials)
			.serviceConfiguration(pathStyle)
			// Newer SDKs add checksum trailers by default that some S3-compatible stores reject: only when required.
			.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
			.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
			.build();
		this.presigner = S3Presigner.builder()
			.endpointOverride(URI.create(s.publicEndpoint()))
			.region(region)
			.credentialsProvider(credentials)
			.serviceConfiguration(pathStyle)
			.build();
	}

	/** Create the bucket at startup. Not fatal if storage is briefly down: the first upload retries it. */
	@Override
	public void run(ApplicationArguments args) {
		try {
			ensureBucket();
		}
		catch (RuntimeException e) {
			log.warn("storage bucket '{}' not ready yet ({}); will retry on first upload", bucket, e.getMessage());
		}
	}

	private synchronized void ensureBucket() {
		if (bucketReady) {
			return;
		}
		try {
			client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
		}
		catch (NoSuchBucketException e) {
			create();
		}
		catch (S3Exception e) {
			if (e.statusCode() == 404) {
				create();
			}
			else {
				throw e;
			}
		}
		bucketReady = true;
	}

	private void create() {
		client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
		log.info("created storage bucket '{}'", bucket);
	}

	@Override
	public void put(String key, byte[] bytes, String contentType) {
		ensureBucket();
		client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
				RequestBody.fromBytes(bytes));
	}

	@Override
	public void delete(String key) {
		try {
			client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
		}
		catch (RuntimeException e) {
			log.warn("could not delete storage object {}: {}", key, e.getMessage());
		}
	}

	@Override
	public String presignedGetUrl(String key, Duration validFor) {
		GetObjectPresignRequest request = GetObjectPresignRequest.builder()
			.signatureDuration(validFor)
			.getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
			.build();
		return presigner.presignGetObject(request).url().toString();
	}

	@PreDestroy
	void close() {
		client.close();
		presigner.close();
	}

}
