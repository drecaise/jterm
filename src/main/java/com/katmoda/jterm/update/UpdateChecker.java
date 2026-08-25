/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.update;

import com.katmoda.jterm.config.JsonStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Asks GitHub for the latest published jterm release. Headless — no Swing, no settings access —
 * so it can be exercised from a plain JVM and unit-tested.
 *
 * <p>This is the only non-SSH outbound connection in the application, and everything it returns
 * is untrusted input that ends up on screen or in a browser. Hence:</p>
 * <ul>
 *   <li>Redirects are <b>not</b> followed. The API never redirects for this endpoint, and a
 *       redirect that appeared would be the interesting case, not a routine one.</li>
 *   <li>The body is read through a hard {@value #MAX_BODY_BYTES}-byte cap. {@code
 *       BodyHandlers.ofByteArray()} is unbounded and would let a hostile or simply broken
 *       endpoint exhaust the heap.</li>
 *   <li>{@link #safeReleaseUrl} re-validates the server-supplied {@code html_url} against this
 *       project's own repository before it can be handed to the system browser.</li>
 *   <li>Nothing about the user is transmitted: no token, no query string, no identifiers — only
 *       the {@code User-Agent} GitHub requires, which carries the jterm version so a broken
 *       release can be correlated in the API logs.</li>
 * </ul>
 *
 * <p>{@code /releases/latest} is used rather than {@code /releases} because GitHub defines it as
 * the newest release that is neither a draft nor a pre-release — users are never offered a beta
 * without the maintainer publishing one as a full release.</p>
 */
public final class UpdateChecker {

    private static final Logger LOG = LoggerFactory.getLogger(UpdateChecker.class);

    /** Repository owner/name, shared by the API call and the URL allow-list. */
    private static final String REPO_PATH = "/drecaise/jterm";

    /** Fallback target whenever the release's own URL fails validation. */
    public static final String RELEASES_PAGE = "https://github.com" + REPO_PATH + "/releases";

    static final String API_URL =
            "https://api.github.com/repos" + REPO_PATH + "/releases/latest";

    /** Well above any realistic release payload, well below anything that threatens the heap. */
    static final int MAX_BODY_BYTES = 1024 * 1024;

    /** Release notes are shown in a scroll pane, not paged — long ones are cut off. */
    static final int MAX_NOTES_CHARS = 4000;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private static volatile HttpClient client;

    private UpdateChecker() {
    }

    /**
     * Fetches the latest published release.
     *
     * @param currentVersion the running version, sent only in the {@code User-Agent}
     * @return the release, or empty if the response carried no usable tag
     * @throws IOException on any transport, timeout, non-200 status, or parse failure —
     *                     callers decide whether that is worth showing the user
     */
    public static Optional<ReleaseInfo> fetchLatest(String currentVersion) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_URL))
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", userAgent(currentVersion))
                .build();

        HttpResponse<InputStream> response;
        try {
            response = client().send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Update check was interrupted", e);
        }

        byte[] body;
        try (InputStream in = response.body()) {
            body = in.readNBytes(MAX_BODY_BYTES);
        }
        if (response.statusCode() != 200) {
            // 403/429 mean the shared unauthenticated rate limit is exhausted (60/hour per IP).
            // There is no retry: the caller records the attempt either way and backs off a day.
            throw new IOException("GitHub returned HTTP " + response.statusCode()
                    + " for the update check");
        }

        ReleaseInfo release = JsonStore.mapper()
                .readValue(new String(body, StandardCharsets.UTF_8), ReleaseInfo.class);
        if (release == null || release.tagName() == null || release.tagName().isBlank()) {
            LOG.debug("update check: response carried no tag_name");
            return Optional.empty();
        }
        return Optional.of(release);
    }

    /**
     * Returns {@code htmlUrl} if it genuinely points at a release of this project, else the
     * project's releases page.
     *
     * <p>This is the load-bearing check of the whole feature: the value reaches {@code
     * BrowserLauncher}, which will hand it to {@code Desktop.browse} or {@code xdg-open}. Host
     * comparison is exact — {@code github.com.example.org} and {@code raw.githubusercontent.com}
     * must both fail — and the path prefix keeps a compromised or mistyped response from pointing
     * at an unrelated repository.</p>
     */
    public static String safeReleaseUrl(String htmlUrl) {
        if (htmlUrl == null || htmlUrl.isBlank()) {
            return RELEASES_PAGE;
        }
        try {
            URI uri = new URI(htmlUrl.trim());
            String path = uri.getPath();
            if ("https".equalsIgnoreCase(uri.getScheme())
                    && "github.com".equalsIgnoreCase(uri.getHost())
                    && path != null
                    && path.startsWith(REPO_PATH + "/releases/")) {
                return uri.toString();
            }
        } catch (URISyntaxException e) {
            LOG.debug("update check: unparsable release URL {}", htmlUrl, e);
        }
        LOG.debug("update check: rejected release URL {}", htmlUrl);
        return RELEASES_PAGE;
    }

    /**
     * Normalises release notes for display as plain text: CRLF stripped, trailing whitespace
     * removed, and truncated at {@link #MAX_NOTES_CHARS} with a marker so the user knows the
     * text continues on the release page.
     */
    public static String trimNotes(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String text = body.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (text.length() <= MAX_NOTES_CHARS) {
            return text;
        }
        return text.substring(0, MAX_NOTES_CHARS).stripTrailing()
                + "\n\n… see the release page for the rest.";
    }

    private static String userAgent(String currentVersion) {
        String version = currentVersion == null || currentVersion.isBlank()
                ? "unknown" : currentVersion.trim();
        // Keep the header to characters that are unambiguously legal in a header value.
        return "jterm/" + version.replaceAll("[^A-Za-z0-9._+-]", "_");
    }

    /** Built lazily so a user who opts out never pays for the client's selector thread. */
    private static HttpClient client() {
        HttpClient local = client;
        if (local == null) {
            synchronized (UpdateChecker.class) {
                local = client;
                if (local == null) {
                    local = HttpClient.newBuilder()
                            .version(HttpClient.Version.HTTP_2)
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .connectTimeout(CONNECT_TIMEOUT)
                            .build();
                    client = local;
                }
            }
        }
        return local;
    }
}
