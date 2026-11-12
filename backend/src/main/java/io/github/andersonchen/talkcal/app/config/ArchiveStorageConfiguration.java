package io.github.andersonchen.talkcal.app.config;

import io.github.andersonchen.talkcal.calendar.adapter.out.archive.s3.ArchiveBucket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 歸檔用的物件儲存（#54）：行程歸檔（calendar 的 adapter）和 AI 助理的對話歸檔（app.job）共用一個 bucket。
 */
@Configuration(proxyBeanMethods = false)
class ArchiveStorageConfiguration {

    /**
     * 連到哪是設定（talkcal.archive.*）：本機是 compose 的 RustFS，雲端是 S3（endpoint 空白）。
     * 建這個物件不會連線，連不上要到真的上傳時才知道 ——
     * 物件儲存掛了不該讓整個 app 起不來，歸檔晚一天沒關係（沒存好的不會被刪）。
     */
    @Bean
    ArchiveBucket archiveBucket(@Value("${talkcal.archive.endpoint}") String endpoint,
                                @Value("${talkcal.archive.region}") String region,
                                @Value("${talkcal.archive.bucket}") String bucket,
                                @Value("${talkcal.archive.access-key}") String accessKey,
                                @Value("${talkcal.archive.secret-key}") String secretKey) {
        return ArchiveBucket.connect(endpoint, region, bucket, accessKey, secretKey);
    }
}
