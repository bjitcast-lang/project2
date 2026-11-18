package com.hmdp.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 放宽时间类型的反序列化格式。
 * <p>
 * Jackson 默认只认 ISO 格式 {@code 2026-02-19T18:17:11}，
 * 而数据库、Apifox、前端常用空格格式 {@code 2026-02-19 18:17:11}
 * 或毫秒时间戳 {@code 1642066339000}。这里让几种都能接收。
 * <p>
 * 注意：只改反序列化，序列化仍保持默认的 ISO 格式，
 * 因为前端用 {@code new Date(createTime)} 解析，ISO 在各浏览器下更安全。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer flexibleLocalDateTime() {
        return builder -> builder.deserializerByType(LocalDateTime.class, new FlexibleLocalDateTimeDeserializer());
    }

    public static class FlexibleLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

        private static final DateTimeFormatter[] FORMATTERS = {
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME
        };

        @Override
        public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            // 毫秒（或秒）时间戳，例如 1642066339000
            if (parser.hasToken(JsonToken.VALUE_NUMBER_INT) || parser.hasToken(JsonToken.VALUE_NUMBER_FLOAT)) {
                return fromEpoch(parser.getLongValue());
            }

            String text = parser.getText();
            if (text == null || text.trim().isEmpty()) {
                return null;
            }
            text = text.trim();

            // 字符串形式的时间戳，例如 "1642066339000"
            if (text.matches("\\d{10,13}")) {
                return fromEpoch(Long.parseLong(text));
            }

            for (DateTimeFormatter formatter : FORMATTERS) {
                try {
                    return LocalDateTime.parse(text, formatter);
                } catch (DateTimeParseException ignored) {
                    // 继续尝试下一种格式
                }
            }

            // 只传了日期时，按当天 00:00:00 处理
            try {
                return LocalDate.parse(text).atStartOfDay();
            } catch (DateTimeParseException ignored) {
                // 全部失败，交给 Jackson 抛出标准错误信息
            }

            return (LocalDateTime) context.handleWeirdStringValue(
                    LocalDateTime.class, text,
                    "无法解析为 LocalDateTime，支持格式：yyyy-MM-dd HH:mm:ss、yyyy-MM-dd'T'HH:mm:ss 或毫秒时间戳");
        }

        /**
         * 把时间戳转成本地时间。10 位按秒处理，13 位按毫秒处理，
         * 时区用系统默认时区，保证和中国时区的数据一致。
         */
        private LocalDateTime fromEpoch(long timestamp) {
            long epochMilli = timestamp < 100_000_000_000L ? timestamp * 1000 : timestamp;
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneId.systemDefault());
        }
    }
}
