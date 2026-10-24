package io.github.andersonchen.talkcal.calendar.adapter.out.archive.s3;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * 歸檔的整合測試用的 S3 相容儲存：跟 deploy/compose.yaml 同一個 RustFS 映像檔。
 *
 * 為什麼不是 MinIO（Testcontainers 有現成的模組）：MinIO 已經不發社群版的映像檔，
 * Docker Hub 上的 minio/minio 拉不到了（2026-10 實測）。RustFS 是 Apache-2.0、講同一套 S3 API 的替代品。
 * 跟真的 S3 一樣走 HTTP、一樣驗簽章 —— 用假的 S3 client 測不出「endpoint、path-style、checksum 設錯」這類問題。
 */
public final class RustFsContainer extends GenericContainer<RustFsContainer> {

    static final String ACCESS_KEY = "archive-test";
    static final String SECRET_KEY = "archive-test-secret";
    static final String REGION = "us-east-1";

    public RustFsContainer() {
        super("rustfs/rustfs:1.0.3");
        withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY);
        withEnv("RUSTFS_SECRET_KEY", SECRET_KEY);
        withExposedPorts(9000);
        // /health/ready：儲存、權限都準備好才回 200（/health 只代表程序活著）
        waitingFor(Wait.forHttp("/health/ready").forPort(9000));
    }

    public String endpoint() {
        return "http://" + getHost() + ":" + getMappedPort(9000);
    }

    /** 連到這個容器裡的某個 bucket（不會自動建立，要先 createBucket）。 */
    public ArchiveBucket bucket(String name) {
        return ArchiveBucket.connect(endpoint(), REGION, name, ACCESS_KEY, SECRET_KEY);
    }

    public void createBucket(String name) {
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint()))
                .forcePathStyle(true)
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .build()) {
            s3.createBucket(r -> r.bucket(name));
        }
    }
}
