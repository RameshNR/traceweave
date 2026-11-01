package com.example.flowtracker.debug;

import com.example.flowtracker.model.FlowValue;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink;
import com.intellij.xdebugger.frame.XFullValueEvaluator;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XValue;
import com.intellij.xdebugger.frame.XValueChildrenList;
import com.intellij.xdebugger.frame.XValueContainer;
import com.intellij.xdebugger.frame.XValueNode;
import com.intellij.xdebugger.frame.XValuePlace;
import com.intellij.xdebugger.frame.presentation.XValuePresentation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Reads a debugger value the way the Variables view shows it (any debugger: Java, Kotlin, JavaScript...): its
 * type and value text, and its children (fields, map entries, array elements) a few levels deep. The debugger
 * answers asynchronously; {@code done} runs on the UI thread once everything asked for has arrived, or after a
 * time limit with what has arrived by then.
 */
public final class FlowValueReader {
    /** How deep children are read: the value, its fields, their fields, ... */
    public static final int DEPTH = 4;
    /** Children read per value (a big list keeps its first ones and says how many more there are). */
    static final int MAX_CHILDREN = 100;
    /** Values read in total, so a huge object graph doesn't fill the workspace file. */
    static final int MAX_VALUES = 3000;
    /** Longest value text kept. */
    static final int MAX_TEXT = 2000;
    static final long TIMEOUT_SECONDS = 20;
    /**
     * How long nothing more must arrive before a read is complete. The debugger may first answer with a
     * placeholder ("Collecting data…", without children) and send the real value a moment later.
     */
    static final long QUIET_MS = 400;

    private FlowValueReader() {}

    /** Reads one value (e.g. the variable right-clicked in the Variables view). */
    public static void read(String name, XValue value, int depth, Consumer<FlowValue> done) {
        Job job = new Job(new FlowValue(name), done);
        job.value(job.root, value, depth);
        job.started();
    }

    /** Reads every variable of a stack frame (the current debug session's data), under one root. */
    public static void readFrame(XStackFrame frame, int depth, Consumer<FlowValue> done) {
        FlowValue root = new FlowValue("Variables (now)");
        Job job = new Job(root, done);
        job.children(root, frame, depth);
        job.started();
    }

    /**
     * Reads the variable of that name in a stack frame (e.g. to compare it with a saved one); {@code done} gets
     * null when the frame has no such variable.
     */
    public static void readVariable(XStackFrame frame, String name, Consumer<FlowValue> done) {
        frame.computeChildren(new XCompositeNode() {
            private boolean found, ended;

            @Override
            public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                for (int i = 0; i < children.size() && !found; i++) {
                    if (!children.getName(i).equals(name)) continue;
                    found = true;
                    read(name, children.getValue(i), DEPTH, done);
                }
                if (last) none();
            }

            private void none() {
                if (found || ended) return;
                ended = true;
                ApplicationManager.getApplication().invokeLater(() -> done.accept(null));
            }

            @SuppressWarnings("deprecation") // abstract in every supported IDE, so it has to be there
            @Override public void tooManyChildren(int remaining) { none(); }
            @Override public void tooManyChildren(int remaining, @NotNull Runnable addNextChildren) { none(); }
            @Override public void setAlreadySorted(boolean alreadySorted) {}
            @Override public void setErrorMessage(@NotNull String errorMessage) { none(); }
            @Override public void setErrorMessage(@NotNull String errorMessage, @Nullable XDebuggerTreeNodeHyperlink link) { none(); }
            @Override public void setMessage(@NotNull String message, @Nullable Icon icon, @NotNull SimpleTextAttributes attributes,
                                             @Nullable XDebuggerTreeNodeHyperlink link) {}
            @Override public boolean isObsolete() { return found || ended; }
        });
    }

    /** One read: counts what is still being asked, and finishes once nothing is (or the time is up). */
    private static final class Job {
        final FlowValue root;
        private final Consumer<FlowValue> done;
        private int pending = 1; // the start itself, until started()
        private int values;
        private boolean finished;
        /** When the debugger last answered anything (System.nanoTime). */
        private long lastAnswer = System.nanoTime();

        synchronized void answered() {
            lastAnswer = System.nanoTime();
        }

        Job(FlowValue root, Consumer<FlowValue> done) {
            this.root = root;
            this.done = done;
            AppExecutorUtil.getAppScheduledExecutorService().schedule(this::finish, TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        void started() {
            settle();
        }

        synchronized boolean finished() {
            return finished;
        }

        /** Asks for a value's text, then its children. */
        void value(FlowValue target, XValue xv, int depth) {
            synchronized (this) {
                if (finished) return;
                pending++;
                values++;
            }
            xv.computePresentation(new PresentationNode(this, target, xv, depth), XValuePlace.TREE);
        }

        /** Asks for the children of a value (or of a stack frame). */
        void children(FlowValue target, XValueContainer container, int depth) {
            synchronized (this) {
                if (finished || depth <= 0 || values >= MAX_VALUES) return;
                pending++;
            }
            container.computeChildren(new ChildrenNode(this, target, depth));
        }

        synchronized boolean roomFor(FlowValue parent) {
            return !finished && parent.children.size() < MAX_CHILDREN && values < MAX_VALUES;
        }

        /** One thing asked has arrived. With nothing left to wait for, the read ends after a quiet moment. */
        void settle() {
            boolean last;
            synchronized (this) {
                pending--;
                lastAnswer = System.nanoTime();
                last = pending <= 0;
            }
            if (last) finishWhenQuiet();
        }

        /** Finishes once nothing is pending and nothing has arrived for {@link #QUIET_MS} (late answers update values). */
        private void finishWhenQuiet() {
            AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
                boolean ready;
                synchronized (this) {
                    if (finished) return;
                    ready = pending <= 0 && System.nanoTime() - lastAnswer >= TimeUnit.MILLISECONDS.toNanos(QUIET_MS);
                }
                if (ready) finish();
                else finishWhenQuiet();
            }, QUIET_MS, TimeUnit.MILLISECONDS);
        }

        void finish() {
            synchronized (this) {
                if (finished) return;
                finished = true;
            }
            if (ApplicationManager.getApplication() == null) done.accept(root); // outside the IDE (tests)
            else ApplicationManager.getApplication().invokeLater(() -> done.accept(root));
        }
    }

    /** Receives a value's text (and whether it has children) from the debugger. */
    private static final class PresentationNode implements XValueNode {
        private final Job job;
        private final FlowValue target;
        private final XValue xv;
        private final int depth;
        private boolean presented, expanded;

        PresentationNode(Job job, FlowValue target, XValue xv, int depth) {
            this.job = job;
            this.target = target;
            this.xv = xv;
            this.depth = depth;
        }

        @Override
        public void setPresentation(@Nullable Icon icon, @Nullable String type, @NotNull String value, boolean hasChildren) {
            present(type, value, hasChildren);
        }

        @Override
        public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
            present(presentation.getType(), text(presentation), hasChildren);
        }

        /**
         * Every answer updates the value: the debugger may first send a placeholder ("Collecting data…", no
         * children) and the real value (with children) later, or a toString() computed afterwards. Children are read
         * the first time an answer says there are some.
         */
        private void present(@Nullable String type, String value, boolean hasChildren) {
            boolean first, expand;
            synchronized (job) {
                if (type != null && !type.isEmpty() || target.type.isEmpty()) target.type = type == null ? "" : type;
                target.value = value.length() > MAX_TEXT ? value.substring(0, MAX_TEXT) + "…" : value;
                first = !presented;
                presented = true;
                expand = hasChildren && !expanded;
                if (expand) expanded = true;
            }
            job.answered();
            if (expand) job.children(target, xv, depth - 1);
            if (first) job.settle();
        }

        @Override
        public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}

        @Override
        public boolean isObsolete() {
            return job.finished();
        }
    }

    /** Receives a value's children from the debugger, possibly in several parts. */
    private static final class ChildrenNode implements XCompositeNode {
        private final Job job;
        private final FlowValue target;
        private final int depth;
        private boolean ended;

        ChildrenNode(Job job, FlowValue target, int depth) {
            this.job = job;
            this.target = target;
            this.depth = depth;
        }

        @Override
        public void addChildren(@NotNull XValueChildrenList children, boolean last) {
            job.answered();
            for (int i = 0; i < children.size(); i++) {
                if (!job.roomFor(target)) {
                    more(children.size() - i);
                    break;
                }
                FlowValue child = new FlowValue(children.getName(i));
                synchronized (job) {
                    target.children.add(child);
                }
                job.value(child, children.getValue(i), depth);
            }
            if (last) end();
        }

        @SuppressWarnings("deprecation") // abstract in every supported IDE, so it has to be there
        @Override
        public void tooManyChildren(int remaining) {
            more(remaining);
            end();
        }

        @Override
        public void tooManyChildren(int remaining, @NotNull Runnable addNextChildren) {
            tooManyChildren(remaining); // the first part is enough: no more children are asked for
        }

        /** A last child that says how many there are beyond the ones read. */
        private void more(int remaining) {
            if (remaining <= 0) return;
            synchronized (job) {
                FlowValue m = new FlowValue("…");
                m.value = remaining + " more not read";
                target.children.add(m);
            }
        }

        @Override
        public void setAlreadySorted(boolean alreadySorted) {}

        @Override
        public void setErrorMessage(@NotNull String errorMessage) {
            synchronized (job) {
                if (target.value.isEmpty()) target.value = errorMessage;
            }
            end();
        }

        @Override
        public void setErrorMessage(@NotNull String errorMessage, @Nullable XDebuggerTreeNodeHyperlink link) {
            setErrorMessage(errorMessage);
        }

        @Override
        public void setMessage(@NotNull String message, @Nullable Icon icon, @NotNull SimpleTextAttributes attributes,
                               @Nullable XDebuggerTreeNodeHyperlink link) {}

        private void end() {
            synchronized (this) {
                if (ended) return;
                ended = true;
            }
            job.settle();
        }

        @Override
        public boolean isObsolete() {
            return job.finished();
        }
    }

    /** A value's text as the Variables view writes it (strings in quotes). */
    static String text(XValuePresentation p) {
        StringBuilder sb = new StringBuilder();
        p.renderValue(new XValuePresentation.XValueTextRenderer() {
            @Override public void renderValue(@NotNull String value) { sb.append(value); }
            @Override public void renderStringValue(@NotNull String value) { sb.append('"').append(value).append('"'); }
            @Override public void renderNumericValue(@NotNull String value) { sb.append(value); }
            @Override public void renderKeywordValue(@NotNull String value) { sb.append(value); }
            @Override public void renderValue(@NotNull String value, @NotNull TextAttributesKey key) { sb.append(value); }
            @Override public void renderStringValue(@NotNull String value, @Nullable String additionalSpecialCharsToHighlight, int maxLength) {
                sb.append('"').append(value).append('"');
            }
            @Override public void renderComment(@NotNull String comment) { sb.append(comment); }
            @Override public void renderSpecialSymbol(@NotNull String symbol) { sb.append(symbol); }
            @Override public void renderError(@NotNull String error) { sb.append(error); }
        });
        return sb.toString();
    }
}
