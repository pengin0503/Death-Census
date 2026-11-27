package com.pengin0503.deathcensus;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Pure multipart/form-data composer for the optional season HTML attachment
 * (DESIGN 1.3.0 §5, §12 question 11).
 *
 * <p>Discord expects a {@code payload_json} part and the file as {@code files[0]}.
 * This class only builds the request body string (HTML is UTF-8 text, so a
 * String round-trip is lossless); the version notifier owns the HttpClient and
 * runs the same PR-2 retry engine. Tests never perform HTTP.
 *
 * <p>The delimiter is generated randomly for every request and is checked
 * against the JSON, file name and HTML before use: player-controlled text
 * rendered into the attachment must not be able to forge a boundary line.
 * Because each body has its own boundary, the authoritative Content-Type for a
 * POST is {@link Body#contentType()} on the very body being sent. The legacy
 * no-argument {@link #contentType()} still returns a valid Content-Type value
 * with a freshly generated boundary, but that value belongs to no particular
 * body and must not be paired with a body composed separately.
 */
public final class DiscordMultipart {
    /** Attachment cap: 1 MB, per §5. */
    public static final long MAX_ATTACHMENT_BYTES = 1024L * 1024L;
    /** Per-request boundaries start with this recognizable, non-player prefix. */
    private static final String BOUNDARY_PREFIX = "----DeathCensusBoundary";
    /** Upper bound on regenerations so a pathological generator cannot hang the caller. */
    private static final int MAX_BOUNDARY_ATTEMPTS = 64;

    private DiscordMultipart() {
    }

    public record Body(String contentType, String body) {
        public Body {
            contentType = contentType == null ? "" : contentType;
            body = body == null ? "" : body;
        }
    }

    /**
     * A Content-Type header value with a freshly generated boundary, retained
     * for source compatibility. Callers sending a composed body must use
     * {@link Body#contentType()} instead, because that value matches the
     * delimiters actually present in that body.
     */
    public static String contentType() {
        return contentTypeFor(newBoundaryCandidate());
    }

    /** Builds the two-part body; {@code payloadJson} must already be serialized. */
    public static Body compose(String payloadJson, String fileName, String html) {
        return compose(payloadJson, fileName, html, DiscordMultipart::newBoundaryCandidate);
    }

    /**
     * Package-visible generator seam used by tests: boundary candidates are
     * drawn from {@code generator} until one is absent from all three parts.
     * The public {@link #compose(String, String, String)} always uses the
     * random UUID-based generator.
     */
    static Body compose(String payloadJson, String fileName, String html, Supplier<String> generator) {
        Objects.requireNonNull(generator, "generator");
        String json = payloadJson == null ? "" : payloadJson;
        String name = fileName == null ? "season.html" : fileName;
        String content = html == null ? "" : html;
        String boundary = chooseBoundary(generator, json, name, content);
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"payload_json\"\r\n")
                .append("Content-Type: application/json\r\n\r\n")
                .append(json).append("\r\n")
                .append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"files[0]\"; filename=\"").append(name).append("\"\r\n")
                .append("Content-Type: text/html; charset=utf-8\r\n\r\n")
                .append(content).append("\r\n")
                .append("--").append(boundary).append("--\r\n");
        return new Body(contentTypeFor(boundary), sb.toString());
    }

    private static String newBoundaryCandidate() {
        return BOUNDARY_PREFIX + UUID.randomUUID();
    }

    private static String contentTypeFor(String boundary) {
        return "multipart/form-data; boundary=" + boundary;
    }

    /** Regenerates until no part contains the candidate as a literal substring. */
    private static String chooseBoundary(Supplier<String> generator, String... parts) {
        for (int attempt = 0; attempt < MAX_BOUNDARY_ATTEMPTS; attempt++) {
            String candidate = generator.get();
            if (candidate == null || candidate.isEmpty()
                    || candidate.indexOf('\r') >= 0 || candidate.indexOf('\n') >= 0) {
                continue;
            }
            boolean collides = false;
            for (String part : parts) {
                if (part.contains(candidate)) {
                    collides = true;
                    break;
                }
            }
            if (!collides) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to generate a multipart boundary absent from all parts");
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
