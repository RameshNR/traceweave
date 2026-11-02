package com.example.flowtracker.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.FrameWrapper;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.awt.Window;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.BooleanSupplier;

/**
 * A window in the IDE's own style (the same title bar as IntelliJ's main window, with Minimize, Maximize and Close),
 * which doesn't block the IDE and remembers its size. Used by the Flow Diagram and the Compare window.
 */
public final class FlowWindow {
    private final FrameWrapper frame;
    private boolean closed;

    /**
     * @param key      remembers the window's size and place under this name
     * @param canClose asked when the user closes the window (e.g. "discard the changes?"); false keeps it open
     */
    public FlowWindow(@Nullable Project project, String key, String title, JComponent content, @Nullable BooleanSupplier canClose) {
        frame = new FrameWrapper(project, key, false, title, content);
        if (canClose != null) onClose(canClose);
        Disposer.register(frame, () -> closed = true);
    }

    /**
     * The close check, given in the form the running IDE expects: 2023.x takes a BooleanGetter, newer IDEs a
     * BooleanSupplier (looked up when the window opens, so both work).
     */
    private void onClose(BooleanSupplier canClose) {
        for (Method m : FrameWrapper.class.getMethods()) {
            if (!m.getName().equals("setOnCloseHandler") || m.getParameterCount() != 1) continue;
            Class<?> type = m.getParameterTypes()[0];
            Object handler = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "equals": return proxy == args[0];
                    case "hashCode": return System.identityHashCode(proxy);
                    case "toString": return "TraceWeave close check";
                    default: return canClose.getAsBoolean();
                }
            });
            try {
                m.invoke(frame, handler);
            } catch (ReflectiveOperationException ignored) {
                // closing then simply closes
            }
            return;
        }
    }

    public void setTitle(String title) {
        frame.setTitle(title);
    }

    public void show() {
        frame.show();
        FlowWindowHeader.match(frame.getFrame());
    }

    /** Closes it without asking (e.g. after Apply). */
    public void close() {
        if (!closed) frame.close();
    }

    public boolean isClosed() {
        return closed;
    }

    public @Nullable Window window() {
        return frame.getFrame();
    }

    /** Runs when the window is gone, however it was closed. */
    public void whenClosed(Runnable r) {
        Disposer.register(frame, r::run);
    }

    /** Shows it again (also when minimized) and puts it in front. */
    public void bringBack() {
        Window w = window();
        if (w == null) return;
        if (w instanceof java.awt.Frame f && (f.getExtendedState() & java.awt.Frame.ICONIFIED) != 0) {
            f.setExtendedState(f.getExtendedState() & ~java.awt.Frame.ICONIFIED);
        }
        w.setVisible(true);
        w.toFront();
        w.requestFocus();
    }

    /** A parent for things that should go away with the window. */
    public Disposable disposable() {
        return frame;
    }
}
