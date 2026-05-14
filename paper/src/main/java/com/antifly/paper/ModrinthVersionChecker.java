package com.antifly.paper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

final class ModrinthVersionChecker {
    private static final Pattern VERSION_NUMBER_PATTERN = Pattern.compile("\"version_number\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern DATE_PUBLISHED_PATTERN = Pattern.compile("\"date_published\"\\s*:\\s*\"([^\"]+)\"");
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private ModrinthVersionChecker() {
    }

    static void checkLatest(JavaPlugin plugin, String projectSlug, VersionResultHandler handler) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            VersionResult result;
            try {
                String encodedSlug = URLEncoder.encode(projectSlug, StandardCharsets.UTF_8);
                URI uri = URI.create("https://api.modrinth.com/v2/project/" + encodedSlug + "/version?featured=true&include_changelog=false");
                HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "AntiFly/" + plugin.getDescription().getVersion() + " (version-check)")
                    .build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    result = VersionResult.error("HTTP " + response.statusCode());
                } else {
                    result = parseLatestVersion(response.body());
                }
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                result = VersionResult.error(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            } catch (RuntimeException ex) {
                result = VersionResult.error(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            }

            VersionResult finalResult = result;
            Bukkit.getScheduler().runTask(plugin, () -> handler.handle(finalResult));
        });
    }

    private static VersionResult parseLatestVersion(String body) {
        Matcher versionMatcher = VERSION_NUMBER_PATTERN.matcher(body);
        Matcher dateMatcher = DATE_PUBLISHED_PATTERN.matcher(body);
        String latestVersion = null;
        String latestDate = null;
        while (versionMatcher.find()) {
            String version = versionMatcher.group(1);
            String date = dateMatcher.find() ? dateMatcher.group(1) : "";
            if (latestVersion == null || date.compareTo(latestDate) > 0) {
                latestVersion = version;
                latestDate = date;
            }
        }
        if (latestVersion == null) {
            return VersionResult.error("No versions found on Modrinth");
        }
        return VersionResult.ok(latestVersion);
    }

    static int compareVersions(String local, String remote) {
        String[] localParts = local.split("[^A-Za-z0-9]+");
        String[] remoteParts = remote.split("[^A-Za-z0-9]+");
        int len = Math.max(localParts.length, remoteParts.length);
        for (int i = 0; i < len; i++) {
            String a = i < localParts.length ? localParts[i] : "0";
            String b = i < remoteParts.length ? remoteParts[i] : "0";
            int cmp;
            if (isDigits(a) && isDigits(b)) {
                cmp = Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
            } else {
                cmp = a.compareToIgnoreCase(b);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    interface VersionResultHandler {
        void handle(VersionResult result);
    }

    static final class VersionResult {
        final boolean ok;
        final String latestVersion;
        final String error;

        private VersionResult(boolean ok, String latestVersion, String error) {
            this.ok = ok;
            this.latestVersion = latestVersion;
            this.error = error;
        }

        static VersionResult ok(String latestVersion) {
            return new VersionResult(true, latestVersion, null);
        }

        static VersionResult error(String error) {
            return new VersionResult(false, null, error);
        }
    }
}

