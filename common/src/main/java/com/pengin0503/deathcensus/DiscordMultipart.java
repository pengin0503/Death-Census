package com.pengin0503.deathcensus;

import java.time.ZoneId;
import java.util.Locale;

/**
 * Pure multipart/form-data composer for the optional season HTML attachment
 * (DESIGN 1.3.0 §5, §12 question 11).
 *
 * <p>Discord expects a {@code payload_json} part and the file as {@code files[0]}.
 * This class only builds the request body string (HTML is UTF-8 text, so a
 * String round-trip is lossless); the version notifier owns the HttpClient and
 * runs the same PR-2 retry engine. A fixed boundary keeps the output
 * deterministic and unit-testable; tests never perform HTTP.
 */
public final class DiscordMultipart {
    /** Attachment cap: 1 MB, per §5. */
    public static final long MAX_ATTACHMENT_BYTES = 1024L * 1024L;
    private static final String BOUNDARY = "----DeathCensusSeasonBoundary7f3a9c";

    private DiscordMultipart() {
    }

    public record Body(String contentType, String body) {
        public Body {
            contentType = contentType == null ? "" : contentType;
            body = body == null ? "" : body;
        }
    }

    /** Content-Type header value (without the parameter prefix) for a composed body. */
    public static String contentType() {
        return "multipart/form-data; boundary=" + BOUNDARY;
    }

    /** Builds the two-part body; {@code payloadJson} must already be serialized. */
    public static Body compose(String payloadJson, String fileName, String html) {
        String json = payloadJson == null ? "" : payloadJson;
        String name = fileName == null ? "season.html" : fileName;
        String content = html == null ? "" : html;
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(BOUNDARY).append("\r\n")
                .append("Content-Disposition: form-data; name=\"payload_json\"\r\n")
                .append("Content-Type: application/json\r\n\r\n")
                .append(json).append("\r\n")
                .append("--").append(BOUNDARY).append("\r\n")
                .append("Content-Disposition: form-data; name=\"files[0]\"; filename=\"").append(name).append("\"\r\n")
                .append("Content-Type: text/html; charset=utf-8\r\n\r\n")
                .append(content).append("\r\n")
                .append("--").append(BOUNDARY).append("--\r\n");
        return new Body(contentType(), sb.toString());
    }

    /** {@code season-<yyyyMMdd>-<yyyyMMdd>.html} in the display time zone. */
    public static String attachmentName(SeasonRange range, ZoneId zone) {
        if (range == null) {
            return "season.html";
        }
        return "season-" + range.fileNameStamp(zone) + ".html";
    }

    /** True when an HTML attachment of {@code bytes} must fall back to content-only. */
    public static boolean tooLarge(long bytes) {
        return bytes > MAX_ATTACHMENT_BYTES;
    }

    /** Human-readable cap for failure messages; never a URL or a secret. */
    public static String sizeLimitLabel() {
        return String.format(Locale.ROOT, "%d MB", MAX_ATTACHMENT_BYTES / (1024L * 1024L));
    }
}
