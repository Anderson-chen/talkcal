package io.github.andersonchen.talkcal.app.config;

import io.github.andersonchen.talkcal.calendar.adapter.out.archive.s3.ArchiveBucket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 連到 S3 或 RustFS 的設定
 */
@Configuration(proxyBeanMethods = false)
class ArchiveStorageConfiguration {

    /** 透過 application.properties 取得連線時需要的資訊 */
    @Bean
    ArchiveBucket archiveBucket(@Value("${talkcal.archive.endpoint}") String endpoint,
                                @Value("${talkcal.archive.region}") String region,
                                @Value("${talkcal.archive.bucket}") String bucket,
                                @Value("${talkcal.archive.access-key}") String accessKey,
                                @Value("${talkcal.archive.secret-key}") String secretKey) {
        return ArchiveBucket.connect(endpoint, region, bucket, accessKey, secretKey);
    }
}
