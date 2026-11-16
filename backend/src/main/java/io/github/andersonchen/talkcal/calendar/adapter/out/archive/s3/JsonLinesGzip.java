package io.github.andersonchen.talkcal.calendar.adapter.out.archive.s3;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 歸檔的格式：一行一筆 JSON（JSON Lines），整包 gzip。
 * - 不用這個 app 也讀得懂：zcat 就是文字，jq、Athena、DuckDB 都能直接讀。歸檔可能放好幾年，那時程式早就改過了
 * - 一行一筆：壞掉一行只影響那一筆，要串流處理時不必整包讀進記憶體
 * - gzip：文字壓縮後通常剩 1/5 到 1/10，每個工具都能解
 * 不用 Parquet：多一套函式庫，對一個人一個月幾十筆的量沒有好處。
 *
 * 每一行的形狀由呼叫端自己的 record 決定，record 要帶版本號 v。
 */
public final class JsonLinesGzip {

    // 設定好之後是執行緒安全的，可以當常數重用。Jackson 3 內建 java.time，日期寫成 ISO 字串（2024-03-01T09:00:00）
    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonLinesGzip() {
    }

    public static byte[] encode(List<?> lines) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Writer out = new OutputStreamWriter(new GZIPOutputStream(bytes), StandardCharsets.UTF_8)) {
            for (Object line : lines) {
                // JSON 字串裡的換行會被跳脫成 \n，所以一筆一定是一行
                out.write(JSON.writeValueAsString(line));
                out.write('\n');
            }
        } catch (IOException | JacksonException e) {
            throw new IllegalStateException("歸檔壓縮失敗", e);
        }
        return bytes.toByteArray();
    }

    public static <T> List<T> decode(byte[] content, Class<T> type) {
        List<T> lines = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new ByteArrayInputStream(content)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isBlank()) {
                    lines.add(JSON.readValue(line, type));
                }
            }
        } catch (IOException | UncheckedIOException | JacksonException e) {
            throw new IllegalStateException("歸檔讀不懂（不是 gzip 的 JSON Lines，或欄位對不上）", e);
        }
        return lines;
    }
}
