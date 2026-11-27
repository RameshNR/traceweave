package com.example.flowtracker.copy;

import com.example.flowtracker.model.FlowEntry;
import com.example.flowtracker.model.FlowLook;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * The flow summary written by an AI the user chose and turned on (Settings → AI Summary; off by default). Nothing here
 * runs by itself: {@link #request} only builds the text, which the user sees in full with where it goes, and
 * {@link #send} is called only after the user confirms it. A local model (Ollama) keeps everything on this machine; a
 * cloud service gets the text shown. The API key is kept in IntelliJ's password safe, never in a file.
 */
public final class FlowAi {
    private FlowAi() {
    }

    public static final String OFF = "OFF", LOCAL = "LOCAL", CLOUD = "CLOUD";
    private static final CredentialAttributes KEY = new CredentialAttributes("TraceWeave AI summary");

    /** True only when the user turned it on in Settings. */
    public static boolean enabled() {
        return !OFF.equals(FlowLook.get().getAiMode());
    }

    /** Where the text goes, in words: "Ollama at http://localhost:11434 (llama3.1), on this machine". */
    public static String destination() {
        FlowLook l = FlowLook.get();
        if (LOCAL.equals(l.getAiMode())) return "the local model " + l.getAiLocalModel() + " at " + l.getAiLocalUrl() + " (on this machine)";
        return "CLAUDE".equals(l.getAiCloudProvider()) ? "Anthropic's Claude API (api.anthropic.com), model " + l.getAiCloudModel()
                : "the OpenAI-compatible service at " + l.getAiCloudUrl() + ", model " + l.getAiCloudModel();
    }

    /**
     * The text to send: the built-in summary, then the flow's lines (file, line, code, note). Only what the flow has; the
     * user sees all of it before anything is sent.
     */
    public static String request(List<FlowEntry> entries, Set<String> links) {
        StringBuilder sb = new StringBuilder("Write a short plain-English walkthrough (one or two paragraphs) of this code flow, "
                + "for a code review or onboarding. Say what each step does and how the data moves; don't invent anything that "
                + "isn't in the lines.\n\nOutline:\n").append(FlowSummary.of(entries, links)).append("\n\nThe lines, in flow order:\n");
        for (FlowEntry e : entries) {
            sb.append(e.fileName).append(':').append(e.line).append("  ").append(FlowFormat.methodLabel(e)).append("  |  ")
                    .append(e.text.strip());
            if (e.note != null && !e.note.isBlank()) sb.append("   // note: ").append(e.note.strip());
            sb.append('\n');
        }
        return sb.toString();
    }

    public static @Nullable String apiKey() {
        Credentials c = PasswordSafe.getInstance().get(KEY);
        return c == null ? null : c.getPasswordAsString();
    }

    public static void setApiKey(@Nullable String key) {
        PasswordSafe.getInstance().set(KEY, key == null || key.isBlank() ? null : new Credentials("api-key", key.strip()));
    }

    /** Sends the text the user confirmed and returns the answer. Call it off the UI thread. */
    public static String send(String text) throws IOException, InterruptedException {
        FlowLook l = FlowLook.get();
        if (!enabled()) throw new IOException("AI Summary is off (Settings → AI Summary).");
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        HttpRequest.Builder req;
        JsonObject body = new JsonObject();
        if (LOCAL.equals(l.getAiMode())) { // Ollama
            body.addProperty("model", l.getAiLocalModel());
            body.addProperty("prompt", text);
            body.addProperty("stream", false);
            req = HttpRequest.newBuilder(URI.create(trim(l.getAiLocalUrl()) + "/api/generate"));
        } else if ("CLAUDE".equals(l.getAiCloudProvider())) {
            String key = apiKey();
            if (key == null) throw new IOException("No API key: add yours in Settings → AI Summary.");
            body.addProperty("model", l.getAiCloudModel());
            body.addProperty("max_tokens", 1024);
            JsonArray messages = new JsonArray();
            JsonObject m = new JsonObject();
            m.addProperty("role", "user");
            m.addProperty("content", text);
            messages.add(m);
            body.add("messages", messages);
            req = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                    .header("x-api-key", key).header("anthropic-version", "2023-06-01");
        } else { // OpenAI-compatible
            String key = apiKey();
            if (key == null) throw new IOException("No API key: add yours in Settings → AI Summary.");
            body.addProperty("model", l.getAiCloudModel());
            JsonArray messages = new JsonArray();
            JsonObject m = new JsonObject();
            m.addProperty("role", "user");
            m.addProperty("content", text);
            messages.add(m);
            body.add("messages", messages);
            req = HttpRequest.newBuilder(URI.create(trim(l.getAiCloudUrl()) + "/chat/completions")).header("Authorization", "Bearer " + key);
        }
        HttpResponse<String> res = http.send(req.header("content-type", "application/json").timeout(Duration.ofMinutes(3))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) throw new IOException("The service answered " + res.statusCode() + ": " + shorten(res.body()));
        return answer(l, res.body());
    }

    /** The answer's text from the service's reply. */
    static String answer(FlowLook l, String reply) throws IOException {
        try {
            JsonObject o = JsonParser.parseString(reply).getAsJsonObject();
            if (LOCAL.equals(l.getAiMode())) return o.get("response").getAsString().strip();
            if ("CLAUDE".equals(l.getAiCloudProvider())) {
                StringBuilder sb = new StringBuilder();
                for (var part : o.getAsJsonArray("content")) {
                    JsonObject p = part.getAsJsonObject();
                    if (p.has("text")) sb.append(p.get("text").getAsString());
                }
                return sb.toString().strip();
            }
            return o.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString().strip();
        } catch (RuntimeException e) {
            throw new IOException("The service's answer couldn't be read: " + shorten(reply), e);
        }
    }

    private static String trim(String url) {
        return url == null ? "" : url.strip().replaceAll("/+$", "");
    }

    private static String shorten(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
