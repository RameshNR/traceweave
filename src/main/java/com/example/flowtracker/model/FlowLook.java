package com.example.flowtracker.model;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * How TraceWeave looks, the same in every project (saved in the IDE's {@code flowTracker.xml}): its own colors or
 * the IntelliJ theme's, the Flow Diagram window's background, and the background of the diagram images it copies.
 */
@Service(Service.Level.APP)
@State(name = "FlowTrackerLook", storages = @Storage("flowTracker.xml"))
public final class FlowLook implements PersistentStateComponent<FlowLook.State> {

    /** A background: the window's theme, or always light / dark. */
    public enum Background { THEME, LIGHT, DARK }

    public static class State {
        /** true = every TraceWeave color comes from the current IntelliJ theme (and editor color scheme). */
        public boolean useIdeTheme = false;
        /** Background of the Flow Diagram window's drawing area. */
        public Background diagramBackground = Background.THEME;
        /** Background of the images Copy PNG / Copy Sequence PNG make: LIGHT or DARK. */
        public Background imageBackground = Background.LIGHT;
        /** Lines dragged by hand in the Flow Diagram may run across the classes (false: they keep off them). */
        public boolean linesOverBoxes = false;
        /** The Flow Diagram's Sync: changes are saved by themselves (no Save needed). On by default, for the layout. */
        public boolean diagramSync = true;
        /** What Sync saves: true = everything (like Save); false = only the layout (positions, lines, looks, notes). */
        public boolean diagramSyncAll = false;
        /**
         * AI for the flow summary: OFF (the default: nothing is sent anywhere), LOCAL (a model on this machine, e.g.
         * Ollama) or CLOUD (a service with the user's own key). Only the user turns it on, in Settings; even then
         * each request is shown and sent only when the user confirms it.
         */
        public String aiMode = "OFF";
        public String aiLocalUrl = "http://localhost:11434";
        public String aiLocalModel = "llama3.1";
        /** CLAUDE (Anthropic's API) or OPENAI (any OpenAI-compatible service, at aiCloudUrl). */
        public String aiCloudProvider = "CLAUDE";
        public String aiCloudUrl = "https://api.openai.com/v1";
        public String aiCloudModel = "claude-opus-5-5";
    }

    private State state = new State();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static FlowLook get() {
        return ApplicationManager.getApplication().getService(FlowLook.class);
    }

    /** True when TraceWeave uses the IntelliJ theme's colors; false outside a running IDE (tests). */
    public static boolean ideTheme() {
        var app = ApplicationManager.getApplication();
        return app != null && !app.isDisposed() && get().state.useIdeTheme;
    }

    public boolean isUseIdeTheme() { return state.useIdeTheme; }

    public void setUseIdeTheme(boolean on) {
        state.useIdeTheme = on;
        fire();
    }

    public Background getDiagramBackground() {
        return state.diagramBackground == null ? Background.THEME : state.diagramBackground;
    }

    public void setDiagramBackground(Background b) {
        state.diagramBackground = b;
        fire();
    }

    /** LIGHT or DARK. */
    public Background getImageBackground() {
        return state.imageBackground == Background.DARK ? Background.DARK : Background.LIGHT;
    }

    public void setImageBackground(Background b) {
        state.imageBackground = b == Background.DARK ? Background.DARK : Background.LIGHT;
        fire();
    }

    public boolean isLinesOverBoxes() { return state.linesOverBoxes; }

    public void setLinesOverBoxes(boolean on) {
        if (state.linesOverBoxes == on) return;
        state.linesOverBoxes = on;
        for (Runnable r : listeners) r.run();
    }

    // ---------- AI summary (off unless the user turns it on) ----------

    public String getAiMode() { return state.aiMode == null ? "OFF" : state.aiMode; }
    public String getAiLocalUrl() { return state.aiLocalUrl; }
    public String getAiLocalModel() { return state.aiLocalModel; }
    public String getAiCloudProvider() { return state.aiCloudProvider; }
    public String getAiCloudUrl() { return state.aiCloudUrl; }
    public String getAiCloudModel() { return state.aiCloudModel; }

    /** Sets the AI summary's settings (from its Settings window: the user's own choice). */
    public void setAi(String mode, String localUrl, String localModel, String provider, String cloudUrl, String cloudModel) {
        state.aiMode = mode;
        state.aiLocalUrl = localUrl;
        state.aiLocalModel = localModel;
        state.aiCloudProvider = provider;
        state.aiCloudUrl = cloudUrl;
        state.aiCloudModel = cloudModel;
        for (Runnable r : listeners) r.run();
    }

    public boolean isDiagramSync() { return state.diagramSync; }

    public void setDiagramSync(boolean on) {
        if (state.diagramSync == on) return;
        state.diagramSync = on;
        for (Runnable r : listeners) r.run();
    }

    public boolean isDiagramSyncAll() { return state.diagramSyncAll; }

    public void setDiagramSyncAll(boolean all) {
        if (state.diagramSyncAll == all) return;
        state.diagramSyncAll = all;
        for (Runnable r : listeners) r.run();
    }

    public void addListener(Runnable l) { listeners.add(l); }
    public void removeListener(Runnable l) { listeners.remove(l); }

    private void fire() {
        for (Runnable l : listeners) l.run();
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State s) {
        state = s;
    }
}
