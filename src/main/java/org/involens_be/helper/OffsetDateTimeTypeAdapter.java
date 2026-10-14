package org.involens_be.helper;

import com.google.gson.*;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Type;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j(topic = "OFFSET-DATE-TIME-ADAPTER")
public class OffsetDateTimeTypeAdapter
        implements JsonSerializer<OffsetDateTime>, JsonDeserializer<OffsetDateTime> {

    /** Serializes a provider effective time using the ISO-8601 offset date-time format. */
    @Override
    public JsonElement serialize(
            OffsetDateTime value,
            Type sourceType,
            JsonSerializationContext context) {
        log.debug("Starting OffsetDateTime serialization. value={}", value);
        JsonElement result = new JsonPrimitive(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value));
        log.debug("OffsetDateTime serialized successfully. value={}", value);
        return result;
    }

    /** Deserializes an ISO-8601 provider effective time while preserving its UTC offset. */
    @Override
    public OffsetDateTime deserialize(
            JsonElement json,
            Type targetType,
            JsonDeserializationContext context) throws JsonParseException {
        String value = json.getAsString();
        log.debug("Starting OffsetDateTime deserialization. value={}", value);
        try {
            OffsetDateTime result = OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            log.debug("OffsetDateTime deserialized successfully. value={}", value);
            return result;
        } catch (RuntimeException exception) {
            log.warn("OffsetDateTime deserialization failed. value={}", value, exception);
            throw new JsonParseException("Invalid ISO-8601 offset date-time: " + value, exception);
        }
    }
}
