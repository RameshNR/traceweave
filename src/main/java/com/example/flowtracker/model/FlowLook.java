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
