package io.github.andersonchen.talkcal.calendar.adapter.out.archive.s3;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * 歸檔用的那一個 bucket（#54）：放一包、讀一包、列出某個前綴底下的、刪掉某個前綴底下的。
 *
 * S3 的 API 是物件儲存的共通語言：雲端是 AWS S3，本機是 RustFS（deploy/compose.yaml），只差 endpoint 和帳密。
 * SDK 的型別、例外只留在這個類別裡，外面拿到的是 key 和 byte[]；S3 的錯一律翻成 IllegalStateException（port 的約定）。
 *
 * 不叫 Adapter：它不實作 core 的 port，是這一包的零件 —— 行程（S3EventArchiveAdapter）和
 * AI 助理的對話（app.job.AssistantMemoryArchive）共用同一個 bucket、同一套讀寫。
 *
 * 上傳成功的意思：PutObject 回 200 才算數，而 SDK v2 預設每次上傳都帶 CRC32 checksum、由服務端驗過才收，
 * 傳到一半斷掉、內容傳壞了都會是例外，不會是「看起來成功」。呼叫端只在 put 正常回傳之後才刪資料庫。
 */
public final class ArchiveBucket {

    // DeleteObjects 一次最多 1000 個 key（S3 的上限）
    private static final int DELETE_BATCH = 1000;

    private final S3Client s3;
    private final String bucket;

    public ArchiveBucket(S3Client s3, String bucket) {
        this.s3 = Objects.requireNonNull(s3, "s3 不可為 null");
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("bucket 不可為空白");
        }
        this.bucket = bucket;
    }

    /**
     * 連到 S3 或 S3 相容的儲存。
     *
     * @param endpoint  空白 = AWS S3 本身；給了就是 S3 相容的服務（本機的 RustFS），並改用 path-style
     *                  （http://host/bucket/key）：自架的服務通常沒有「bucket 名稱當子網域」的 DNS
     * @param accessKey 空白 = SDK 的預設憑證鏈（環境變數、EC2 角色…）；給了就用這組固定的
     */
    public static ArchiveBucket connect(String endpoint, String region, String bucket, String accessKey, String secretKey) {
        AwsCredentialsProvider credentials = accessKey == null || accessKey.isBlank()
                ? DefaultCredentialsProvider.builder().build()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        var builder = S3Client.builder().region(Region.of(region)).credentialsProvider(credentials);
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        return new ArchiveBucket(builder.build(), bucket);
    }

    public void put(String key, byte[] content) {
        try {
            s3.putObject(r -> r.bucket(bucket).key(key).contentType("application/gzip"), RequestBody.fromBytes(content));
        } catch (SdkException e) {
            throw new IllegalStateException("歸檔上傳失敗：s3://" + bucket + "/" + key, e);
        }
    }

    public byte[] get(String key) {
        try {
            return s3.getObjectAsBytes(r -> r.bucket(bucket).key(key)).asByteArray();
        } catch (SdkException e) {
            throw new IllegalStateException("歸檔讀取失敗：s3://" + bucket + "/" + key, e);
        }
    }

    /** prefix 底下的全部 key，依字典順序（S3 列出來就是這個順序）。超過 1000 個會自動翻頁。 */
    public List<String> list(String prefix) {
        try {
            return s3.listObjectsV2Paginator(r -> r.bucket(bucket).prefix(prefix)).contents().stream()
                    .map(S3Object::key)
                    .toList();
        } catch (SdkException e) {
            throw new IllegalStateException("歸檔列出失敗：s3://" + bucket + "/" + prefix, e);
        }
    }

    /** 刪掉 prefix 底下的全部，回傳刪了幾個。 */
    public int deleteAll(String prefix) {
        List<String> keys = list(prefix);
        try {
            for (int from = 0; from < keys.size(); from += DELETE_BATCH) {
                List<ObjectIdentifier> batch = keys.subList(from, Math.min(from + DELETE_BATCH, keys.size())).stream()
                        .map(key -> ObjectIdentifier.builder().key(key).build())
                        .toList();
                var response = s3.deleteObjects(r -> r.bucket(bucket).delete(Delete.builder().objects(batch).build()));
                if (response.hasErrors() && !response.errors().isEmpty()) {
                    throw new IllegalStateException("歸檔刪除失敗：" + response.errors());
                }
            }
        } catch (SdkException e) {
            throw new IllegalStateException("歸檔刪除失敗：s3://" + bucket + "/" + prefix, e);
        }
        return keys.size();
    }
}
