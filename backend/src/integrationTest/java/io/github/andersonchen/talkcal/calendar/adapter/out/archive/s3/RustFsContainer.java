package io.github.andersonchen.talkcal.calendar.adapter.out.archive.s3;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * 映像檔跟 deploy/compose.yaml 用同一個。不用 Testcontainers 現成的 MinIO 模組：MinIO 不再發社群版的映像檔，
 * Docker Hub 上的 minio/minio 已經拉不到。RustFS 是 Apache-2.0，S3 API 相同。
 * 用真的儲存，不用假的 S3 client：走 HTTP、驗簽章，endpoint、path-style、checksum 設錯才測得出來。
 */
public final class RustFsContainer extends GenericContainer<RustFsContainer> {

    // public：其他 package 的系統測試要把帳密填進 talkcal.archive.*
    public static final String ACCESS_KEY = "archive-test";
    public static final String SECRET_KEY = "archive-test-secret";
    public static final String REGION = "us-east-1";

    public RustFsContainer() {
        super("rustfs/rustfs:1.0.3");
        withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY);
        withEnv("RUSTFS_SECRET_KEY", SECRET_KEY);
        withExposedPorts(9000);
        // /health 只代表 process 還在，/health/ready 要儲存和權限都準備好才回 200
        waitingFor(Wait.forHttp("/health/ready").forPort(9000));
    }

    public String endpoint() {
        return "http://" + getHost() + ":" + getMappedPort(9000);
    }

    /** bucket 不會自動建立，要先 createBucket。 */
    public ArchiveBucket bucket(String name) {
        return ArchiveBucket.connect(endpoint(), REGION, name, ACCESS_KEY, SECRET_KEY);
    }

    public void createBucket(String name) {
        try (S3Client s3 = client()) {
            s3.createBucket(r -> r.bucket(name));
        }
    }

    /** 用完要 close */
    public S3Client client() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint()))
                .forcePathStyle(true)
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .build();
    }
}
