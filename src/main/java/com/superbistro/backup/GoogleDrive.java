package com.superbistro.backup;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Minimal Google Drive client over plain HTTPS (no Google SDK). Uses the "drive.file" scope, so
 * Super Bistro can only see files it created itself, never the rest of the Drive.
 */
public final class GoogleDrive {
    public static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    public static final String FOLDER_NAME = "Super Bistro Backups";
    private static final String AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN = "https://oauth2.googleapis.com/token";
    private static final String API = "https://www.googleapis.com/drive/v3";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files";

    public record DriveFile(String id, String name, String createdTime) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String clientId;
    private final String clientSecret;

    public GoogleDrive(String clientId, String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public String authUrl(String redirectUri, String state) {
        return AUTH + "?" + form(Map.of(
            "client_id", clientId, "redirect_uri", redirectUri, "response_type", "code", "scope", SCOPE,
            "access_type", "offline", "prompt", "consent", "include_granted_scopes", "true", "state", state));
    }

    /** Exchanges the code from Google's redirect for a long-lived refresh token. */
    public String exchangeCode(String code, String redirectUri) throws IOException {
        JsonNode r = postForm(TOKEN, Map.of("code", code, "client_id", clientId, "client_secret", clientSecret,
            "redirect_uri", redirectUri, "grant_type", "authorization_code"));
        if (!r.hasNonNull("refresh_token")) {
            throw new IOException("Google did not return a refresh token. Remove Super Bistro from your Google account's "
                + "third-party access and connect again.");
        }
        return r.get("refresh_token").asString();
    }

    public String accessToken(String refreshToken) throws IOException {
        JsonNode r = postForm(TOKEN, Map.of("refresh_token", refreshToken, "client_id", clientId,
            "client_secret", clientSecret, "grant_type", "refresh_token"));
        return r.get("access_token").asString();
    }

    public String accountEmail(String token) throws IOException {
        JsonNode r = get(API + "/about?fields=user(emailAddress)", token);
        return r.path("user").path("emailAddress").asString("");
    }

    /** Returns the id of the backups folder, creating it if needed. */
    public String ensureFolder(String token, String knownId) throws IOException {
        if (knownId != null && !knownId.isBlank()) {
            try {
                JsonNode f = get(API + "/files/" + knownId + "?fields=id,trashed", token);
                if (!f.path("trashed").asBoolean(false)) {
                    return knownId;
                }
            } catch (IOException ignored) {
                // deleted or no longer visible: make a new one
            }
        }
        String body = "{\"name\":\"" + FOLDER_NAME + "\",\"mimeType\":\"application/vnd.google-apps.folder\"}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(API + "/files?fields=id"))
            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return send(req).get("id").asString();
    }

    /** Resumable upload, so large backups survive slow connections. Returns the Drive file id. */
    public String upload(String token, String folderId, Path file) throws IOException {
        String meta = "{\"name\":\"" + file.getFileName() + "\",\"parents\":[\"" + folderId + "\"]}";
        HttpRequest start = HttpRequest.newBuilder(URI.create(UPLOAD + "?uploadType=resumable&fields=id"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("X-Upload-Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofString(meta)).build();
        HttpResponse<String> r = call(start);
        if (r.statusCode() != 200) {
            throw new IOException("Google Drive refused the upload (" + r.statusCode() + "): " + r.body());
        }
        String session = r.headers().firstValue("Location").orElseThrow(() -> new IOException("Google Drive gave no upload address"));
        HttpRequest put = HttpRequest.newBuilder(URI.create(session))
            .timeout(Duration.ofMinutes(30))
            .header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofFile(file)).build();
        return send(put).get("id").asString();
    }

    /** Backups in the folder, newest first. */
    public List<DriveFile> list(String token, String folderId) throws IOException {
        String q = URLEncoder.encode("'" + folderId + "' in parents and trashed = false", StandardCharsets.UTF_8);
        JsonNode r = get(API + "/files?q=" + q + "&orderBy=createdTime%20desc&pageSize=200&fields=files(id,name,createdTime)", token);
        List<DriveFile> out = new ArrayList<>();
        for (JsonNode f : r.path("files")) {
            out.add(new DriveFile(f.get("id").asString(), f.get("name").asString(), f.path("createdTime").asString("")));
        }
        return out;
    }

    public void delete(String token, String fileId) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(API + "/files/" + fileId))
            .header("Authorization", "Bearer " + token).DELETE().build();
        HttpResponse<String> r = call(req);
        if (r.statusCode() != 204 && r.statusCode() != 404) {
            throw new IOException("Couldn't delete old backup from Drive (" + r.statusCode() + ")");
        }
    }

    public void revoke(String refreshToken) {
        try {
            postForm("https://oauth2.googleapis.com/revoke", Map.of("token", refreshToken));
        } catch (IOException ignored) {
            // already revoked: fine
        }
    }

    // ------------------------------------------------------------------ http

    private JsonNode get(String url, String token) throws IOException {
        return send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token).GET().build());
    }

    private JsonNode postForm(String url, Map<String, String> params) throws IOException {
        return send(HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form(params))).build());
    }

    private JsonNode send(HttpRequest req) throws IOException {
        HttpResponse<String> r = call(req);
        if (r.statusCode() / 100 != 2) {
            String detail = r.body();
            try {
                JsonNode e = json.readTree(r.body());
                detail = e.path("error_description").asString(e.path("error").path("message").asString(e.path("error").asString(r.body())));
            } catch (Exception ignored) {
                // not JSON
            }
            throw new IOException("Google said (" + r.statusCode() + "): " + detail);
        }
        return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    private HttpResponse<String> call(HttpRequest req) throws IOException {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private static String form(Map<String, String> params) {
        return params.entrySet().stream()
            .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    }
}
