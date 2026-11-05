package com.example.flowtracker.code;

import com.example.flowtracker.model.FlowEntry;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.JavaRecursiveElementWalkingVisitor;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiCall;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiMethodReferenceExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.psi.search.searches.OverridingMethodsSearch;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The code that leads from one line (or method) of the flow to another: A → B → C → D → E, as several <b>ways</b>
 * when there are several, the closest first.
 * <p>
 * It follows what the start works with, not just any calls: the variables the first line makes or uses (e.g.
 * {@code hotelService}), the variables made from them ({@code bookingService}), the parameters they are passed to
 * and, when a call is made on a tracked object, that object's fields ({@code BookingService.hotelService}). A call
 * that touches those costs less than one that doesn't, so the way that carries the data comes first. Calls are
 * followed into the methods that implement or override the called one (an interface's implementations, e.g. a
 * Spring bean injected as its interface), through method references ({@code list.forEach(service::handle)}) and
 * from {@code publishEvent(new X(…))} to Spring's {@code @EventListener} / {@code ApplicationListener} methods for X.
 * In each method on a way, every line that makes the next call and touches the tracked values is kept (both lines of
 * bookTrip() that call travel.getDestination()). Run in a read action.
 */
public final class FlowCallPath {
    private FlowCallPath() {}

    /** How many calls deep it looks, and how many methods it reads at most. */
    static final int MAX_DEPTH = 12, MAX_METHODS = 4000;
    /** How many ways it finds at most. */
    public static final int MAX_WAYS = 10;
    /** What a call costs: one that uses the tracked values, and one that doesn't. */
    private static final int ON_TRACK = 1, OFF_TRACK = 4;

    /** The lines between the two (in order; empty when there are none), or null when no way was found. */
    /** @param cost how far the way strays from what the first line starts (lower is closer; for picking a start line) */
    public record Result(@Nullable List<FlowEntry> lines, String message, int cost) {
        Result(@Nullable List<FlowEntry> lines, String message) {
            this(lines, message, lines == null ? Integer.MAX_VALUE : 0);
        }
    }

    /**
     * A method on a way, with the lines it makes the next call on (for the last method: the end line itself, only
     * to show it).
     *
     * @param via how it was reached when that isn't a plain call: "implements PaymentGateway.pay()", "method
     *            reference", "Spring event BookingCreated" ("" otherwise)
     */
    public record Step(String className, String methodName, String fileName, List<FlowEntry> lines, String via) {
        public String label() {
            return className + "." + methodName + "()";
        }
    }

    /**
     * One way from the start to the end.
     *
     * @param lines   the lines to add between the two (the start line and the end method's line not included)
     * @param carries the start's values the way carries (e.g. "bookingService"), empty when it doesn't use them
     */
    public record Way(List<Step> steps, List<FlowEntry> lines, int cost, List<String> carries) {
        /** "Main.main() → BookingService.bookTrip() → Travel.getDestination()" */
        public String chain() {
            return String.join(" → ", steps.stream().map(Step::label).toList());
        }

        public int calls() {
            return steps.size() - 1;
        }
    }

    /** The ways found (the closest first; empty when none), and what to say about them. */
    public record Ways(List<Way> ways, String message) {}

    /** Told how far the search is; asked whether to stop. */
    public interface Progress {
        Progress NONE = new Progress() {
            @Override public boolean cancelled() { return false; }
            @Override public void read(int methods, int ways) {}
        };

        boolean cancelled();

        /** @param methods how many methods were read so far; @param ways how many ways were found so far */
        void read(int methods, int ways);

        /** A way was found (each once, in the order they are found: the closest first). */
        default void found(Way way) {}
    }

    private static final com.intellij.openapi.diagnostic.Logger LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance("TraceWeave.ConnectFlow");

    /** The closest way from a line to another (the code after the first line). */
    public static Result between(Project project, FlowEntry from, FlowEntry to) {
        return result(ways(project, from, to, null, 1, Progress.NONE));
    }

    /**
     * The closest way from a method to a method: all of the first method is looked at (not only the code after one
     * line), following what its flow lines work with.
     *
     * @param flowLines the first method's lines in the flow (at least one)
     */
    public static Result betweenMethods(Project project, List<FlowEntry> flowLines, FlowEntry to) {
        return result(ways(project, flowLines.get(0), to, flowLines, 1, Progress.NONE));
    }

    private static Result result(Ways w) {
        if (w.ways().isEmpty()) return new Result(null, w.message());
        Way best = w.ways().get(0);
        return new Result(best.lines(), w.message(), best.cost());
    }

    /**
     * The ways from a line, or a method, to another method, the closest first.
     *
     * @param flowLines null: from the line {@code from} on (its own calls come first); else the whole method of
     *                  {@code from}, following what these lines of it (its lines in the flow) work with
     * @param max       how many ways at most
     */
    public static Ways ways(Project project, FlowEntry from, FlowEntry to, @Nullable List<FlowEntry> flowLines, int max,
                            Progress progress) {
        return session(project, from, to, flowLines, max).next(max, progress);
    }

    /**
     * A search that can be paused and gone on with: {@link Session#next} finds ways up to a number, and stops when
     * told; the next call goes on from where it was (what was read and found is kept), e.g. to resume it or to find
     * more ways. Read the code in a read action, one call at a time.
     *
     * @param cap how often one method may be gone on from (more allows more ways through it; the most ways wanted)
     */
    public static Session session(Project project, FlowEntry from, FlowEntry to, @Nullable List<FlowEntry> flowLines, int cap) {
        PsiElement startAt = elementAt(project, from);
        PsiMethod a = methodAt(startAt);
        PsiElement endAt = elementAt(project, to);
        PsiMethod e = methodAt(endAt);
        if (a == null) return new Session(null, new Ways(List.of(), describe(from) + " isn't inside a Java or Kotlin method."));
        if (e == null) return new Session(null, new Ways(List.of(), describe(to) + " isn't inside a Java or Kotlin method."));
        if (a.equals(e)) {
            Step only = step(project, a, List.of(), "");
            return new Session(null, new Ways(List.of(new Way(List.of(only), List.of(), 0, List.of())),
                    "Both lines are in " + a.getName() + "()."));
        }
        return new Session(new Search(project, a, e, startAt, flowLines, Math.max(1, cap), from, to), null);
    }

    /** A search kept between runs (see {@link #session}). */
    public static final class Session {
        private final @Nullable Search search;
        private final @Nullable Ways fixed;

        private Session(@Nullable Search search, @Nullable Ways fixed) {
            this.search = search;
            this.fixed = fixed;
        }

        /**
         * Goes on until {@code max} ways are found in all (those found before count), it is told to stop, or there is
         * nothing left to look at. The ways are every one found so far; each new one is told to {@code progress}.
         */
        public Ways next(int max, Progress progress) {
            return fixed != null ? fixed : search.next(Math.max(1, max), progress);
        }

        /** True when every way there is was found (nothing is left to look at). */
        public boolean exhausted() {
            return fixed != null || search.exhausted();
        }
    }

    /** One search: the methods' calls are read once and shared by the ways. */
    private static final class Search {
        final Project project;
        final PsiMethod a, e;
        final ProjectFileIndex index;
        final GlobalSearchScope scope;
        final String base;
        final int startOffset, cap;
        final @Nullable PsiElement startStatement;
        final Set<String> startNames;
        final FlowEntry from, to;
        Progress progress = Progress.NONE;
        // where the search is (kept between runs: a pause or more ways goes on from here)
        // fixed (cost, order) entries: on equal cost the call made first in the code wins (bookTrip() before a later
        // cancelBooking() that also reaches the end)
        final PriorityQueue<State> queue = new PriorityQueue<>(Comparator.comparingInt(State::cost).thenComparingInt(State::order));
        final Map<PsiMethod, Integer> taken = new HashMap<>(); // how often each method was gone on from (≤ cap)
        final List<Way> ways = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        int read, order;
        /** The calls each method makes, by the method they reach (read once). */
        final Map<PsiMethod, Map<PsiMethod, Edge>> edges = new HashMap<>();
        final List<String> unresolved = new ArrayList<>(), outside = new ArrayList<>(); // for the log

        /** The calls of a method that reach another one, and how it is reached ("" for a plain call). */
        record Edge(List<PsiElement> calls, String via) {}

        /** A partial way: its last method, what it cost, the calls into that method and what it tracks there. */
        record State(PsiMethod method, int cost, int order, @Nullable State parent, List<PsiElement> calls, String via,
                     Set<String> names, int depth) {
            boolean has(PsiMethod m) {
                for (State s = this; s != null; s = s.parent) if (s.method.equals(m)) return true;
                return false;
            }
        }

        Search(Project project, PsiMethod a, PsiMethod e, PsiElement startAt, @Nullable List<FlowEntry> flowLines, int cap,
               FlowEntry from, FlowEntry to) {
            this.project = project;
            this.a = a;
            this.e = e;
            this.cap = cap;
            this.from = from;
            this.to = to;
            index = ProjectFileIndex.getInstance(project);
            scope = GlobalSearchScope.projectScope(project);
            base = project.getBasePath();
            boolean wholeMethod = flowLines != null;
            startOffset = wholeMethod ? -1 : lineStart(startAt);
            // the start line's statement (a call written over several lines is one statement): its own calls come first
            startStatement = wholeMethod ? null : PsiTreeUtil.getParentOfType(startAt, PsiStatement.class, false);
            if (wholeMethod) {
                Set<String> names = new LinkedHashSet<>();
                for (FlowEntry l : flowLines) {
                    PsiElement at = elementAt(project, l);
                    if (at != null && a.equals(methodAt(at))) names.addAll(namesOn(at));
                }
                startNames = spread(a, names, -1);
            } else {
                startNames = spread(a, namesOn(startAt), startOffset);
            }
            queue.add(new State(a, 0, order++, null, List.of(), "", startNames, 0));
        }

        /** True when nothing is left to look at (every way there is was found). */
        boolean exhausted() {
            return queue.isEmpty() || read >= MAX_METHODS;
        }

        Ways next(int max, Progress progress) {
            this.progress = progress;
            while (!queue.isEmpty() && read < MAX_METHODS && ways.size() < max) {
                if (progress.cancelled()) break;
                com.intellij.openapi.progress.ProgressManager.checkCanceled(); // Cancel: the code lookups stop too
                State s = queue.poll();
                try {
                    advance(s);
                } catch (com.intellij.openapi.progress.ProcessCanceledException stopped) {
                    queue.add(s); // not done: the next run takes it again (nothing of it was kept)
                    throw stopped;
                }
            }
            return result(max);
        }

        /** Takes one partial way on: the end (a way found), or the calls its method makes. */
        private void advance(State s) {
            if (s.method.equals(e)) {
                Way w = way(s);
                if (seen.add(w.chain())) {
                    ways.add(w);
                    progress.found(w); // shown at once, while the search goes on
                }
                progress.read(read, ways.size());
                return;
            }
            int n = taken.getOrDefault(s.method, 0) + 1;
            if (n > cap || s.depth >= MAX_DEPTH) {
                taken.put(s.method, n);
                return;
            }
            Map<PsiMethod, Edge> out = edges(s.method); // may be stopped: then nothing is changed yet
            taken.put(s.method, n);
            read++;
            if (read % 25 == 0) progress.read(read, ways.size());
            {
                for (Map.Entry<PsiMethod, Edge> next : out.entrySet()) {
                    PsiMethod t = next.getKey();
                    if (s.has(t)) continue; // no going round in circles
                    List<PsiElement> all = next.getValue().calls();
                    List<PsiElement> onTrack = new ArrayList<>();
                    for (PsiElement c : all) if (touches(c, s.names)) onTrack.add(c);
                    List<PsiElement> fromLine = new ArrayList<>();
                    if (s.parent == null && startStatement != null) {
                        for (PsiElement c : all) if (PsiTreeUtil.isAncestor(startStatement, c, false)) fromLine.add(c);
                    }
                    // the start line's own call (bookingService.bookTrip(…) on it) is the way on, at no cost
                    if (!fromLine.isEmpty()) onTrack = fromLine;
                    int step = !fromLine.isEmpty() ? 0 : onTrack.isEmpty() ? OFF_TRACK : ON_TRACK;
                    // into the end method: every call to it (bookTrip() calls getDestination() on two lines: both)
                    List<PsiElement> used = t.equals(e) ? all : onTrack.isEmpty() ? List.of(all.get(0)) : onTrack;
                    queue.add(new State(t, s.cost + step, order++, s, used, next.getValue().via(), carry(s.names, used, t),
                            s.depth + 1));
                }
            }
        }

        /** The ways found so far, and what to say about them. */
        private Ways result(int max) {
            progress.read(read, ways.size());
            List<Way> ways = new ArrayList<>(this.ways);
            if (!ways.isEmpty()) {
                Way best = ways.get(0);
                String msg = (ways.size() == 1 ? "Found the way from " : "Found " + ways.size() + " ways from ")
                        + a.getName() + "() to " + e.getName() + "()"
                        + (ways.size() == 1 ? " (" + best.calls() + (best.calls() == 1 ? " call, " : " calls, ")
                        + best.lines().size() + (best.lines().size() == 1 ? " line)." : " lines).") : ".");
                if (progress.cancelled()) msg += " (Stopped: there may be more.)";
                return new Ways(ways, msg);
            }
            if (progress.cancelled()) return new Ways(List.of(), "Stopped before a way was found.");
            LOG.info("No way from " + a.getName() + " to " + e.getName() + ": read " + read + " methods; calls not resolved "
                    + unresolved + "; outside the project " + outside + "; dumb " + com.intellij.openapi.project.DumbService.isDumb(project));
            if (read <= 1 && !unresolved.isEmpty() && noneResolve(a, startOffset)) {
                return new Ways(List.of(), "IntelliJ can't resolve the code of " + from.fileName
                        + " (none of its calls lead anywhere). Is the project set up? Check that its Gradle/Maven sync worked"
                        + " and the code isn't red, then connect again.");
            }
            return new Ways(List.of(), "No calls lead from " + a.getName() + "() to " + e.getName() + "() in the project's code"
                    + " (looked " + MAX_DEPTH + " calls deep" + (unresolved.isEmpty() ? "" : "; " + unresolved.size()
                    + " calls couldn't be read: is the project still indexing?") + ").");
        }

        /** A found way: its steps and the lines to add. */
        Way way(State end) {
            List<State> chain = new ArrayList<>();
            for (State s = end; s != null; s = s.parent) chain.add(0, s);
            List<Step> steps = new ArrayList<>();
            LinkedHashSet<FlowEntry> lines = new LinkedHashSet<>();
            Set<String> taken = new HashSet<>();
            for (int i = 0; i < chain.size(); i++) {
                State s = chain.get(i);
                List<FlowEntry> own = new ArrayList<>();
                if (i + 1 < chain.size()) {
                    for (PsiElement call : chain.get(i + 1).calls) {
                        if (startStatement != null && PsiTreeUtil.isAncestor(startStatement, call, false)) continue; // the start line
                        FlowEntry entry = entryAt(project, call);
                        if (entry != null && taken.add(entry.fileUrl + ":" + entry.line)) {
                            own.add(entry);
                            lines.add(entry);
                        }
                    }
                }
                steps.add(step(project, s.method, own, s.via));
            }
            // the start's values the way carries: those its first call touches
            List<String> carries = new ArrayList<>();
            if (chain.size() > 1) {
                Set<String> used = new HashSet<>();
                for (PsiElement c : chain.get(1).calls) {
                    PsiElement where = PsiTreeUtil.getParentOfType(c, PsiStatement.class, false);
                    used.addAll(referenced(where != null ? where : c));
                }
                for (String n : startNames) if (used.contains(n) && !"this".equals(n)) carries.add(n);
            }
            return new Way(steps, new ArrayList<>(lines), end.cost, carries);
        }

        /** The methods a method calls (from the start line on, in the first method), with the calls that reach each. */
        Map<PsiMethod, Edge> edges(PsiMethod m) {
            Map<PsiMethod, Edge> known = edges.get(m);
            if (known != null) return known;
            Map<PsiMethod, List<PsiElement>> calls = new LinkedHashMap<>();
            Map<PsiMethod, String> via = new HashMap<>();
            for (PsiElement call : callsOf(m, m.equals(a) ? startOffset : -1)) {
                com.intellij.openapi.progress.ProgressManager.checkCanceled(); // a method with many calls: Cancel still stops it
                List<Target> targets = new ArrayList<>(targetsOf(call));
                targets.addAll(threadTargets(call)); // a task given to a thread / executor: its run() / call()
                targets.addAll(apiTargets(call)); // an HTTP call to a path: the method that handles it
                if (targets.isEmpty() && unresolved.size() < 15) unresolved.add(m.getName() + ": " + shortText(call));
                for (Target target : targets) {
                    for (PsiMethod c : candidates(target.method())) {
                        PsiMethod nav = c.getNavigationElement() instanceof PsiMethod pm ? pm : c;
                        if (!nav.equals(e) && !inProject(nav)) continue; // only the project's code
                        calls.computeIfAbsent(nav, k -> new ArrayList<>()).add(call);
                        String how = !target.via().isEmpty() ? target.via()
                                : c != target.method() ? "implements " + owner(target.method()) + "." + target.method().getName() + "()" : "";
                        String task = taskGivenTo(call, m);
                        if (task != null) how = (how.isEmpty() ? "" : how + ", ") + "in the task given to " + task;
                        via.putIfAbsent(nav, how);
                    }
                }
            }
            Map<PsiMethod, Edge> out = new LinkedHashMap<>();
            calls.forEach((t, list) -> out.put(t, new Edge(list, via.getOrDefault(t, ""))));
            edges.put(m, out);
            return out;
        }

        /**
         * The method a call's lambda is given to, e.g. "ApiThreadManager.execute()" for a call made in
         * {@code threadManager.execute(() -> get(url))} (that method runs it, often in another thread); null when the
         * call isn't in a lambda given to a call.
         */
        @Nullable String taskGivenTo(PsiElement call, PsiMethod m) {
            com.intellij.psi.PsiLambdaExpression lambda = PsiTreeUtil.getParentOfType(call, com.intellij.psi.PsiLambdaExpression.class, true, PsiMethod.class);
            if (lambda == null || !(lambda.getParent() instanceof PsiExpressionList list) || !(list.getParent() instanceof PsiCall owner)) return null;
            PsiMethod x = owner.resolveMethod();
            if (x == null || x.equals(m)) return null;
            return owner(x) + "." + x.getName() + "()";
        }

        boolean inProject(PsiMethod m) {
            VirtualFile vf = m.getContainingFile() == null ? null : m.getContainingFile().getVirtualFile();
            if (vf == null) return false;
            if (index.isInContent(vf)) return true;
            if (base != null && vf.getPath().replace('\\', '/').startsWith(base.replace('\\', '/'))) return true;
            if (outside.size() < 5 && vf.getPath().endsWith(".java")) outside.add(vf.getPath());
            return false;
        }

        /** A method a call may run, and how ("" for a plain call). */
        record Target(PsiMethod method, String via) {}

        /**
         * The methods a call may run: what it resolves to; when the IDE can't resolve it, the methods of that name
         * and argument count in the class of the object it is made on, else in the project. A method reference runs
         * the method it names; publishEvent(new X()) runs Spring's listeners of X.
         */
        List<Target> targetsOf(PsiElement call) {
            List<Target> out = new ArrayList<>();
            if (call instanceof PsiMethodReferenceExpression ref) {
                if (ref.resolve() instanceof PsiMethod m) out.add(new Target(m, "method reference"));
                return out;
            }
            if (!(call instanceof PsiCall c)) { // Kotlin: a call or a callable reference, read through UAST
                org.jetbrains.uast.UElement u = org.jetbrains.uast.UastContextKt.toUElement(call);
                if (u instanceof org.jetbrains.uast.UCallExpression uc && uc.resolve() != null) {
                    out.add(new Target(uc.resolve(), ""));
                } else if (u instanceof org.jetbrains.uast.UCallableReferenceExpression ur && ur.resolve() instanceof PsiMethod m) {
                    out.add(new Target(m, "method reference"));
                }
                return out;
            }
            PsiMethod resolved = c.resolveMethod();
            if (resolved != null) {
                out.add(new Target(resolved, ""));
                if (call instanceof PsiMethodCallExpression mc) out.addAll(springListeners(mc, resolved));
                return out;
            }
            if (!(call instanceof PsiMethodCallExpression mc)) return out;
            for (com.intellij.psi.JavaResolveResult r : mc.getMethodExpression().multiResolve(true)) {
                if (r.getElement() instanceof PsiMethod pm) out.add(new Target(pm, ""));
            }
            if (!out.isEmpty()) return out;
            String name = mc.getMethodExpression().getReferenceName();
            if (name == null) return out;
            int args = mc.getArgumentList().getExpressionCount();
            PsiExpression q = mc.getMethodExpression().getQualifierExpression();
            PsiClass cls = q == null ? PsiTreeUtil.getParentOfType(call, PsiClass.class)
                    : q.getType() instanceof PsiClassType ct ? ct.resolve() : null;
            if (cls != null) {
                for (PsiMethod x : cls.findMethodsByName(name, true)) {
                    if (x.getParameterList().getParametersCount() == args) out.add(new Target(x, ""));
                }
            }
            if (out.isEmpty()) {
                for (PsiMethod x : PsiShortNamesCache.getInstance(project).getMethodsByName(name, scope)) {
                    if (x.getParameterList().getParametersCount() == args) out.add(new Target(x, ""));
                }
            }
            return out;
        }

        // ---------- threads ----------

        /**
         * A task that runs in another thread: a project class implementing Runnable / Callable given to a call or a
         * new (new Thread(task), executor.submit(task), CompletableFuture.runAsync(task)...), or a new instance of a
         * class extending Thread: its run() / call().
         */
        List<Target> threadTargets(PsiElement call) {
            List<Target> out = new ArrayList<>();
            if (!(call instanceof PsiCall c)) return out;
            List<PsiClass> tasks = new ArrayList<>();
            PsiExpressionList args = c.getArgumentList();
            if (args != null) {
                for (PsiExpression arg : args.getExpressions()) {
                    PsiClass k = arg.getType() instanceof PsiClassType t ? t.resolve() : null;
                    if (k != null) tasks.add(k);
                }
            }
            if (call instanceof PsiNewExpression n && n.getClassReference() != null && n.getClassReference().resolve() instanceof PsiClass k
                    && inherits(k, "java.lang.Thread")) {
                tasks.add(k);
            }
            for (PsiClass k : tasks) {
                for (String[] kind : new String[][]{{"java.lang.Runnable", "run"}, {"java.util.concurrent.Callable", "call"},
                        {"java.lang.Thread", "run"}}) {
                    if (!inherits(k, kind[0]) || k.getQualifiedName() != null && k.getQualifiedName().equals(kind[0])) continue;
                    for (PsiMethod run : k.findMethodsByName(kind[1], false)) {
                        if (run.getParameterList().getParametersCount() == 0 && run.getBody() != null) {
                            out.add(new Target(run, "runs in a thread"));
                        }
                    }
                }
            }
            return out;
        }

        static boolean inherits(PsiClass k, String qualifiedName) {
            if (qualifiedName.equals(k.getQualifiedName())) return true;
            if (com.intellij.psi.util.InheritanceUtil.isInheritor(k, qualifiedName)) return true;
            // a JDK the IDE can't read fully: the name written in implements / extends
            String simple = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
            for (PsiClassType t : k.getSuperTypes()) if (simple.equals(t.getClassName())) return true;
            return false;
        }

        // ---------- API calls ----------

        /** A method that handles an HTTP path (its segments; "{x}" matches any one). */
        record Handler(String path, String method, PsiMethod handler) {}

        private @Nullable List<Handler> handlers;
        private static final java.util.regex.Pattern URL_PATH = java.util.regex.Pattern.compile("(?:https?://[^/\\s\"]+)?(/[A-Za-z0-9_\\-./{}]+)");

        /**
         * An HTTP call to a path (a URL or "/path" in its arguments, or in the variables they are set from): the
         * project's methods that handle that path (Spring's @GetMapping / @RequestMapping..., JAX-RS @Path,
         * HttpServer.createContext("/path", handler) → handler.handle()).
         */
        List<Target> apiTargets(PsiElement call) {
            List<Target> out = new ArrayList<>();
            if (!(call instanceof PsiCall c) || c.getArgumentList() == null) return out;
            Set<String> paths = new LinkedHashSet<>();
            for (PsiExpression arg : c.getArgumentList().getExpressions()) collectPaths(arg, paths, 0);
            if (paths.isEmpty()) return out;
            for (Handler h : handlers()) {
                for (String p : paths) {
                    if (pathMatches(p, h.path())) {
                        out.add(new Target(h.handler(), "API call " + (h.method().isEmpty() ? "" : h.method() + " ") + h.path()));
                        break;
                    }
                }
            }
            return out;
        }

        /** The paths in an expression's string literals (and in the variables it reads, a few levels back). */
        private void collectPaths(PsiElement e, Set<String> out, int depth) {
            if (depth > 3) return;
            // the whole text an argument makes: BASE + "/api/report/" + city + "?days=3" → "http://…/api/report/{x}?days=3"
            if (e instanceof PsiExpression x && depth == 0) addPath(textOf(x, 0), out);
            for (com.intellij.psi.PsiLiteralExpression lit : PsiTreeUtil.findChildrenOfType(e, com.intellij.psi.PsiLiteralExpression.class)) {
                if (lit.getValue() instanceof String s) addPath(s, out);
            }
            if (e instanceof com.intellij.psi.PsiLiteralExpression lit && lit.getValue() instanceof String s) addPath(s, out);
            List<PsiReferenceExpression> refs = new ArrayList<>(PsiTreeUtil.findChildrenOfType(e, PsiReferenceExpression.class));
            if (e instanceof PsiReferenceExpression r) refs.add(r);
            for (PsiReferenceExpression r : refs) {
                if (r.resolve() instanceof com.intellij.psi.PsiVariable v && v.getInitializer() != null) collectPaths(v.getInitializer(), out, depth + 1);
            }
        }

        /** The text an expression makes: its literals joined, what isn't known as "{x}" (variables are followed back). */
        private static String textOf(PsiExpression e, int depth) {
            if (e instanceof com.intellij.psi.PsiLiteralExpression lit) return lit.getValue() instanceof String s ? s : "{x}";
            if (e instanceof com.intellij.psi.PsiParenthesizedExpression p && p.getExpression() != null) return textOf(p.getExpression(), depth);
            if (e instanceof com.intellij.psi.PsiPolyadicExpression poly
                    && poly.getOperationTokenType() == com.intellij.psi.JavaTokenType.PLUS) {
                StringBuilder sb = new StringBuilder();
                for (PsiExpression op : poly.getOperands()) sb.append(textOf(op, depth));
                return sb.toString();
            }
            if (depth < 3 && e instanceof PsiReferenceExpression r && r.resolve() instanceof com.intellij.psi.PsiVariable v
                    && v.getInitializer() != null) {
                return textOf(v.getInitializer(), depth + 1);
            }
            return "{x}";
        }

        private static void addPath(String s, Set<String> out) {
            java.util.regex.Matcher m = URL_PATH.matcher(s.strip());
            if (m.lookingAt() && m.group(1).length() > 1) out.add(m.group(1));
        }

        /** True when a called path is the handler's path ({x} segments match any one; the caller may add a prefix). */
        static boolean pathMatches(String called, String handler) {
            String[] c = trim(called).split("/"), h = trim(handler).split("/");
            if (h.length == 0 || h.length > c.length) return false;
            int off = c.length - h.length; // a context path or host part before it
            for (int i = 0; i < h.length; i++) {
                if (h[i].startsWith("{") && h[i].endsWith("}")) continue;
                if (c[off + i].startsWith("{") && c[off + i].endsWith("}")) continue; // a part the caller fills in
                if (!h[i].equalsIgnoreCase(c[off + i])) return false;
            }
            return true;
        }

        private static String trim(String p) {
            String s = p;
            int q = s.indexOf('?');
            if (q >= 0) s = s.substring(0, q);
            while (s.startsWith("/")) s = s.substring(1);
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            return s;
        }

        /** The project's HTTP handlers (read once per search). */
        List<Handler> handlers() {
            if (handlers != null) return handlers;
            List<Handler> out = new ArrayList<>();
            JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
            GlobalSearchScope all = GlobalSearchScope.allScope(project);
            String[][] spring = {{"RequestMapping", ""}, {"GetMapping", "GET"}, {"PostMapping", "POST"}, {"PutMapping", "PUT"},
                    {"DeleteMapping", "DELETE"}, {"PatchMapping", "PATCH"}};
            for (String[] s : spring) {
                PsiClass ann = facade.findClass("org.springframework.web.bind.annotation." + s[0], all);
                if (ann == null) continue;
                for (PsiMethod m : AnnotatedElementsSearch.searchPsiMethods(ann, scope).findAll()) {
                    String own = annotationPath(m.getModifierList().findAnnotation(ann.getQualifiedName()));
                    String prefix = m.getContainingClass() == null ? "" : annotationPath(m.getContainingClass().getModifierList() == null ? null
                            : m.getContainingClass().getModifierList().findAnnotation("org.springframework.web.bind.annotation.RequestMapping"));
                    out.add(new Handler(join(prefix, own), s[1], m));
                }
            }
            for (String jaxrs : new String[]{"javax.ws.rs.Path", "jakarta.ws.rs.Path"}) {
                PsiClass ann = facade.findClass(jaxrs, all);
                if (ann == null) continue;
                for (PsiMethod m : AnnotatedElementsSearch.searchPsiMethods(ann, scope).findAll()) {
                    PsiClass k = m.getContainingClass();
                    String prefix = k == null || k.getModifierList() == null ? "" : annotationPath(k.getModifierList().findAnnotation(jaxrs));
                    out.add(new Handler(join(prefix, annotationPath(m.getModifierList().findAnnotation(jaxrs))), "", m));
                }
            }
            // com.sun.net.httpserver: server.createContext("/path", new Handler()) → Handler.handle(exchange)
            PsiClass server = facade.findClass("com.sun.net.httpserver.HttpServer", all);
            if (server != null) {
                for (PsiMethod create : server.findMethodsByName("createContext", false)) {
                    for (com.intellij.psi.PsiReference ref : com.intellij.psi.search.searches.MethodReferencesSearch.search(create, scope, true).findAll()) {
                        PsiMethodCallExpression mc = PsiTreeUtil.getParentOfType(ref.getElement(), PsiMethodCallExpression.class);
                        if (mc == null || mc.getArgumentList().getExpressionCount() < 2) continue;
                        PsiExpression[] args = mc.getArgumentList().getExpressions();
                        if (!(args[0] instanceof com.intellij.psi.PsiLiteralExpression lit) || !(lit.getValue() instanceof String path)) continue;
                        PsiClass k = args[1].getType() instanceof PsiClassType t ? t.resolve() : null;
                        if (k == null) continue;
                        for (PsiMethod handle : k.findMethodsByName("handle", false)) {
                            if (handle.getBody() != null) out.add(new Handler(path, "", handle));
                        }
                    }
                }
            }
            handlers = out;
            return out;
        }

        private static String annotationPath(@Nullable com.intellij.psi.PsiAnnotation a) {
            if (a == null) return "";
            for (String attr : new String[]{"value", "path"}) {
                com.intellij.psi.PsiAnnotationMemberValue v = a.findDeclaredAttributeValue(attr);
                if (v instanceof com.intellij.psi.PsiArrayInitializerMemberValue arr && arr.getInitializers().length > 0) v = arr.getInitializers()[0];
                if (v instanceof com.intellij.psi.PsiLiteralExpression lit && lit.getValue() instanceof String s) return s;
            }
            return "";
        }

        private static String join(String prefix, String own) {
            String p = trim(prefix), o = trim(own);
            return "/" + (p.isEmpty() ? o : o.isEmpty() ? p : p + "/" + o);
        }

        /** Listeners cached by event class. */
        final Map<PsiClass, List<PsiMethod>> listeners = new HashMap<>();

        /**
         * Spring: {@code publishEvent(event)} runs the {@code @EventListener} methods that take the event's class (or
         * a class it extends) and the {@code onApplicationEvent} methods of the matching ApplicationListeners.
         */
        List<Target> springListeners(PsiMethodCallExpression call, PsiMethod resolved) {
            if (!"publishEvent".equals(resolved.getName()) || call.getArgumentList().getExpressionCount() != 1) return List.of();
            PsiType type = call.getArgumentList().getExpressions()[0].getType();
            PsiClass event = type instanceof PsiClassType ct ? ct.resolve() : null;
            if (event == null) return List.of();
            List<PsiMethod> found = listeners.computeIfAbsent(event, this::listenersOf);
            List<Target> out = new ArrayList<>();
            for (PsiMethod m : found) out.add(new Target(m, "Spring event " + event.getName()));
            return out;
        }

        List<PsiMethod> listenersOf(PsiClass event) {
            List<PsiMethod> out = new ArrayList<>();
            PsiClass annotation = JavaPsiFacade.getInstance(project).findClass("org.springframework.context.event.EventListener",
                    GlobalSearchScope.allScope(project));
            if (annotation != null) {
                for (PsiMethod m : AnnotatedElementsSearch.searchPsiMethods(annotation, scope).findAll()) {
                    if (takes(m, event)) out.add(m);
                }
            }
            for (PsiMethod m : PsiShortNamesCache.getInstance(project).getMethodsByName("onApplicationEvent", scope)) {
                if (takes(m, event)) out.add(m);
            }
            return out;
        }

        /** True when a method's only parameter accepts the event (its class or one the event extends). */
        static boolean takes(PsiMethod m, PsiClass event) {
            if (m.getParameterList().getParametersCount() != 1) return false;
            PsiType p = m.getParameterList().getParameters()[0].getType();
            PsiClass wanted = p instanceof PsiClassType ct ? ct.resolve() : null;
            return wanted != null && (wanted.equals(event) || event.isInheritor(wanted, true));
        }

        /** The method itself and, when it can be overridden, the project's methods that override it. */
        List<PsiMethod> candidates(PsiMethod target) {
            List<PsiMethod> out = new ArrayList<>();
            out.add(target);
            if (!target.hasModifierProperty(PsiModifier.FINAL) && !target.hasModifierProperty(PsiModifier.STATIC)
                    && !target.hasModifierProperty(PsiModifier.PRIVATE) && !target.isConstructor()) {
                int n = 0;
                for (PsiMethod o : OverridingMethodsSearch.search(target, scope, true).findAll()) {
                    out.add(o);
                    if (++n >= 20) break;
                }
            }
            return out;
        }
    }

    private static Step step(Project project, PsiMethod m, List<FlowEntry> lines, String via) {
        PsiFile file = m.getContainingFile();
        return new Step(owner(m), m.getName(), file == null ? "" : file.getName(), lines, via);
    }

    private static String owner(PsiMethod m) {
        PsiClass c = m.getContainingClass();
        return c == null || c.getName() == null ? "?" : c.getName();
    }

    /** True when none of a method's calls (from an offset on) resolve: the IDE can't read its code. */
    private static boolean noneResolve(PsiMethod m, int from) {
        for (PsiElement c : callsOf(m, from)) if (c instanceof PsiCall pc && pc.resolveMethod() != null) return false;
        return true;
    }

    private static String shortText(PsiElement e) {
        String t = e.getText().replaceAll("\\s+", " ");
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }

    /**
     * The calls a method makes (method calls, new …(), method references), in order; from an offset on when ≥ 0. A
     * Kotlin function's calls and callable references are read through UAST (their Kotlin code elements are given).
     */
    private static List<PsiElement> callsOf(PsiMethod m, int from) {
        List<PsiElement> out = new ArrayList<>();
        if (m.getBody() == null) {
            org.jetbrains.uast.UMethod u = org.jetbrains.uast.UastContextKt.toUElement(m, org.jetbrains.uast.UMethod.class);
            if (u == null || u.getSourcePsi() == null || u.getSourcePsi() instanceof PsiMethod) return out; // no code (abstract, a library)
            u.accept(new org.jetbrains.uast.visitor.AbstractUastVisitor() {
                @Override public boolean visitCallExpression(@org.jetbrains.annotations.NotNull org.jetbrains.uast.UCallExpression node) {
                    add(node.getSourcePsi());
                    return false; // the arguments can hold calls too
                }

                @Override public boolean visitCallableReferenceExpression(
                        @org.jetbrains.annotations.NotNull org.jetbrains.uast.UCallableReferenceExpression node) {
                    add(node.getSourcePsi());
                    return false;
                }

                private void add(@Nullable PsiElement psi) {
                    if (psi != null && psi.getTextOffset() >= from && !out.contains(psi)) out.add(psi);
                }
            });
            out.sort(Comparator.comparingInt(PsiElement::getTextOffset));
            return out;
        }
        m.getBody().accept(new JavaRecursiveElementWalkingVisitor() {
            @Override public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                if (expression.getTextOffset() >= from) out.add(expression);
            }

            @Override public void visitNewExpression(PsiNewExpression expression) {
                super.visitNewExpression(expression);
                if (expression.getTextOffset() >= from) out.add(expression);
            }

            @Override public void visitMethodReferenceExpression(PsiMethodReferenceExpression expression) {
                super.visitMethodReferenceExpression(expression);
                if (expression.getTextOffset() >= from) out.add(expression);
            }
        });
        return out;
    }

    /** True when a call (or the statement it is in) uses one of the tracked names. */
    private static boolean touches(PsiElement call, Set<String> names) {
        if (names.isEmpty()) return false;
        PsiElement where = PsiTreeUtil.getParentOfType(call, PsiStatement.class, false);
        Set<String> used = referenced(where != null ? where : call);
        for (String n : names) if (used.contains(n)) return true;
        return false;
    }

    /** The names a line makes or uses: a variable declared or assigned on it, else every name it reads. */
    private static Set<String> namesOn(PsiElement at) {
        PsiStatement st = PsiTreeUtil.getParentOfType(at, PsiStatement.class, false);
        PsiElement scope = st != null ? st : at;
        Set<String> made = new LinkedHashSet<>();
        for (PsiLocalVariable v : PsiTreeUtil.findChildrenOfType(scope, PsiLocalVariable.class)) made.add(v.getName());
        if (scope instanceof PsiLocalVariable v) made.add(v.getName());
        for (PsiAssignmentExpression as : PsiTreeUtil.findChildrenOfType(scope, PsiAssignmentExpression.class)) {
            if (as.getLExpression() instanceof PsiReferenceExpression r && r.getReferenceName() != null) made.add(r.getReferenceName());
        }
        if (made.isEmpty()) return referenced(scope);
        // the objects the line's calls are made on (bookingService in "booking = bookingService.bookTrip(…)")
        for (PsiMethodCallExpression mc : PsiTreeUtil.findChildrenOfType(scope, PsiMethodCallExpression.class)) {
            if (mc.getMethodExpression().getQualifierExpression() instanceof PsiReferenceExpression q && q.getReferenceName() != null) {
                made.add(q.getReferenceName());
            }
        }
        return made;
    }

    /** The variable names an element reads. */
    private static Set<String> referenced(PsiElement e) {
        Set<String> out = new HashSet<>();
        for (PsiReferenceExpression r : PsiTreeUtil.findChildrenOfType(e, PsiReferenceExpression.class)) {
            if (r.getReferenceName() != null) out.add(r.getReferenceName());
        }
        if (e instanceof PsiReferenceExpression r && r.getReferenceName() != null) out.add(r.getReferenceName());
        return out;
    }

    /** In a method: the tracked names, and the local variables made from them (from an offset on, ≥ 0). */
    private static Set<String> spread(PsiMethod m, Set<String> names, int from) {
        Set<String> out = new LinkedHashSet<>(names);
        if (m.getBody() == null) return out;
        boolean grew = true;
        for (int round = 0; grew && round < 5; round++) {
            grew = false;
            for (PsiLocalVariable v : PsiTreeUtil.findChildrenOfType(m.getBody(), PsiLocalVariable.class)) {
                if (from >= 0 && v.getTextOffset() < from || out.contains(v.getName()) || v.getInitializer() == null) continue;
                for (String n : out) {
                    if (referenced(v.getInitializer()).contains(n)) {
                        out.add(v.getName());
                        grew = true;
                        break;
                    }
                }
            }
        }
        return out;
    }

    /**
     * What is tracked in a method called with tracked values: its parameters that get them, the fields of its class
     * with a tracked name (e.g. a field hotelService set from the parameter of the same name), and what is made from
     * those in it.
     */
    private static Set<String> carry(Set<String> names, List<PsiElement> calls, PsiMethod into) {
        Set<String> in = new LinkedHashSet<>();
        PsiParameter[] params = into.getParameterList().getParameters();
        for (PsiElement call : calls) {
            PsiExpressionList args = call instanceof PsiCall c ? c.getArgumentList() : null;
            if (args != null) {
                PsiExpression[] exprs = args.getExpressions();
                for (int i = 0; i < exprs.length && i < params.length; i++) {
                    for (String n : names) if (referenced(exprs[i]).contains(n)) in.add(params[i].getName());
                }
            }
            // the object the call is made on: when it is tracked, so are its fields
            PsiExpression qualifier = call instanceof PsiMethodCallExpression mc ? mc.getMethodExpression().getQualifierExpression()
                    : call instanceof PsiMethodReferenceExpression ref ? ref.getQualifierExpression() : null;
            if (qualifier != null) {
                for (String n : names) if (referenced(qualifier).contains(n)) in.add("this");
            }
            // a method reference used on tracked data (tracked.forEach(service::handle)): its parameters get it
            if (call instanceof PsiMethodReferenceExpression && touches(call, names)) {
                for (PsiParameter p : params) in.add(p.getName());
            }
        }
        PsiClass cls = into.getContainingClass();
        if (cls != null) {
            // the object the call is made on is tracked: so is everything it holds (bookingService.bookTrip(…) works
            // with bookingService's hotelService); else only its fields of a tracked name (passed along by name)
            boolean self = in.contains("this");
            for (PsiField f : cls.getAllFields()) if (self || names.contains(f.getName())) in.add(f.getName());
        }
        // (other names don't carry over: a local "booking" of another method is another variable)
        return spread(into, in, -1);
    }

    /** The first code element on a saved line (not the indentation). */
    private static @Nullable PsiElement elementAt(Project project, FlowEntry entry) {
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(entry.fileUrl);
        Document doc = vf == null ? null : FileDocumentManager.getInstance().getDocument(vf);
        PsiFile psi = doc == null ? null : com.intellij.psi.PsiManager.getInstance(project).findFile(vf);
        if (psi == null || entry.line < 1 || entry.line > doc.getLineCount()) return null;
        int start = doc.getLineStartOffset(entry.line - 1), end = doc.getLineEndOffset(entry.line - 1);
        CharSequence text = doc.getCharsSequence();
        for (int off = start; off < end; off++) {
            if (Character.isWhitespace(text.charAt(off))) continue;
            PsiElement at = psi.findElementAt(off);
            if (at != null) return at;
        }
        return null;
    }

    /** The method a code element is in: a Java method, or a Kotlin function (as the method Java sees). */
    private static @Nullable PsiMethod methodAt(@Nullable PsiElement at) {
        if (at == null) return null;
        PsiMethod java = PsiTreeUtil.getParentOfType(at, PsiMethod.class, false);
        if (java != null) return java;
        org.jetbrains.uast.UMethod u = FlowLang.uParent(FlowLang.uElementAt(at), org.jetbrains.uast.UMethod.class);
        return u == null ? null : u.getJavaPsi();
    }

    private static int lineStart(PsiElement at) {
        PsiStatement st = PsiTreeUtil.getParentOfType(at, PsiStatement.class, false);
        return (st != null ? st : at).getTextRange().getStartOffset();
    }

    /** A flow line for the line a call is on. */
    private static @Nullable FlowEntry entryAt(Project project, PsiElement call) {
        PsiFile file = call.getContainingFile();
        VirtualFile vf = file == null ? null : file.getVirtualFile();
        Document doc = vf == null ? null : FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return null;
        // the line the call is made on: where the called method's name is (travel.getDestination() on the second
        // line of "double hotelCost = hotelService.calculateCost(\n travel.getDestination(), …" is that second line)
        PsiElement name = call instanceof PsiMethodCallExpression mc ? mc.getMethodExpression().getReferenceNameElement()
                : call instanceof PsiMethodReferenceExpression ref ? ref.getReferenceNameElement() : null;
        int at = name != null ? name.getTextOffset() : call.getTextOffset();
        return FlowCapture.lineEntry(project, doc, doc.getLineNumber(at)); // lines in the flow already count too
    }

    private static String describe(FlowEntry e) {
        return e.fileName + ":" + e.line;
    }
}
