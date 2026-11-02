package com.example.flowtracker.code;

import com.example.flowtracker.model.FlowEntry;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.JavaRecursiveElementWalkingVisitor;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiCall;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.OverridingMethodsSearch;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The code that leads from one line of the flow to another: A → B → C → D → E. It follows what the first line
 * starts, not just any calls: from the first line on (not the code before it), it tracks the variables the line
 * makes or uses (e.g. {@code hotelService}), the variables made from them ({@code bookingService}), the parameters
 * they are passed to and the fields of the same name in the classes called ({@code BookingService.hotelService}).
 * The way with the fewest calls that don't touch those is taken (a call that does costs less), into overriding
 * methods too, until the second line's method is reached. In each method on the way, every line that makes the next
 * call and touches the tracked values is kept (e.g. both lines of bookTrip() that call travel.getDestination() with
 * hotelService). Run in a read action.
 */
public final class FlowCallPath {
    private FlowCallPath() {}

    /** How many calls deep it looks, and how many methods it reads at most. */
    static final int MAX_DEPTH = 12, MAX_METHODS = 4000;
    /** What a call costs: one that uses the tracked values, and one that doesn't. */
    private static final int ON_TRACK = 1, OFF_TRACK = 4;

    /** The lines between the two (in order; empty when there are none), or null when no way was found. */
    /** @param cost how far the way strays from what the first line starts (lower is closer; for picking a start line) */
    public record Result(@Nullable List<FlowEntry> lines, String message, int cost) {
        Result(@Nullable List<FlowEntry> lines, String message) {
            this(lines, message, lines == null ? Integer.MAX_VALUE : 0);
        }
    }

    private record Hop(PsiMethod from, List<PsiCall> calls) {}
    private record Queued(PsiMethod method, int cost, int order) {}

    private static final com.intellij.openapi.diagnostic.Logger LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance("TraceWeave.ConnectFlow");

    public static Result between(Project project, FlowEntry from, FlowEntry to) {
        return between(project, from, to, null);
    }

    /**
     * A method to a method: all of the first method is looked at (not only the code after one line), following what
     * its flow lines work with (bookingService in "BookingService bookingService = …"): its first such call that
     * leads to the end wins.
     *
     * @param flowLines the first method's lines in the flow (at least one)
     */
    public static Result betweenMethods(Project project, List<FlowEntry> flowLines, FlowEntry to) {
        return between(project, flowLines.get(0), to, flowLines);
    }

    private static Result between(Project project, FlowEntry from, FlowEntry to, @Nullable List<FlowEntry> flowLines) {
        boolean wholeMethod = flowLines != null;
        PsiElement startAt = elementAt(project, from);
        PsiMethod a = startAt == null ? null : PsiTreeUtil.getParentOfType(startAt, PsiMethod.class, false);
        PsiElement endAt = elementAt(project, to);
        PsiMethod e = endAt == null ? null : PsiTreeUtil.getParentOfType(endAt, PsiMethod.class, false);
        if (a == null) return new Result(null, describe(from) + " isn't inside a Java method.");
        if (e == null) return new Result(null, describe(to) + " isn't inside a Java method.");
        if (a.equals(e)) return new Result(List.of(), "Both lines are in " + a.getName() + "().");

        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        int startOffset = wholeMethod ? -1 : lineStart(startAt);
        // the start line's statement (a call written over several lines is one statement): its own calls come first
        PsiElement startStatement = wholeMethod ? null : PsiTreeUtil.getParentOfType(startAt, PsiStatement.class, false);

        // what the first line starts: the variables it makes or uses
        Map<PsiMethod, Set<String>> tracked = new HashMap<>();
        if (wholeMethod) {
            Set<String> names = new LinkedHashSet<>();
            for (FlowEntry l : flowLines) {
                PsiElement at = elementAt(project, l);
                if (at != null && a.equals(PsiTreeUtil.getParentOfType(at, PsiMethod.class, false))) names.addAll(namesOn(at));
            }
            tracked.put(a, spread(a, names, -1));
        } else {
            tracked.put(a, spread(a, namesOn(startAt), startOffset));
        }

        Map<PsiMethod, Integer> cost = new HashMap<>();
        Map<PsiMethod, Integer> depth = new HashMap<>();
        Map<PsiMethod, Hop> came = new HashMap<>();
        // fixed (cost, order) entries: on equal cost the call made first in the code wins (bookTrip() before a later
        // cancelBooking() that also reaches the end), and a method's cost changing later doesn't upset the queue
        PriorityQueue<Queued> queue = new PriorityQueue<>(
                java.util.Comparator.comparingInt(Queued::cost).thenComparingInt(Queued::order));
        int order = 0;
        cost.put(a, 0);
        depth.put(a, 0);
        queue.add(new Queued(a, 0, order++));
        Set<PsiMethod> done = new HashSet<>();
        int read = 0;
        List<String> unresolved = new ArrayList<>(), outside = new ArrayList<>(); // why a call wasn't followed (for the log)
        String base = project.getBasePath();
        while (!queue.isEmpty() && read++ < MAX_METHODS) {
            PsiMethod m = queue.poll().method();
            if (!done.add(m)) continue;
            if (m.equals(e)) break;
            if (depth.get(m) >= MAX_DEPTH) continue;
            Set<String> names = tracked.getOrDefault(m, Set.of());
            // every method this one calls (from the first line on, in the first method), with the calls that reach it
            Map<PsiMethod, List<PsiCall>> next = new java.util.LinkedHashMap<>();
            for (PsiCall call : calls(m, m.equals(a) ? startOffset : -1)) {
                List<PsiMethod> targets = targetsOf(project, call);
                if (targets.isEmpty() && unresolved.size() < 15) unresolved.add(m.getName() + ": " + shortText(call));
                for (PsiMethod target : targets) {
                    for (PsiMethod c : candidates(target, scope)) {
                        PsiMethod nav = c.getNavigationElement() instanceof PsiMethod pm ? pm : c;
                        VirtualFile vf = nav.getContainingFile() == null ? null : nav.getContainingFile().getVirtualFile();
                        boolean mine = vf != null && (index.isInContent(vf) || base != null
                                && vf.getPath().replace('\\', '/').startsWith(base.replace('\\', '/')));
                        if (!nav.equals(e) && !mine) { // only the project's code
                            if (vf != null && outside.size() < 5 && vf.getPath().endsWith(".java")) outside.add(vf.getPath());
                            continue;
                        }
                        next.computeIfAbsent(nav, k -> new ArrayList<>()).add(call);
                    }
                }
            }
            for (Map.Entry<PsiMethod, List<PsiCall>> n : next.entrySet()) {
                PsiMethod t = n.getKey();
                List<PsiCall> onTrack = new ArrayList<>();
                for (PsiCall c : n.getValue()) if (touches(c, names)) onTrack.add(c);
                boolean fromStartLine = false;
                for (PsiCall c : n.getValue()) fromStartLine |= startStatement != null && PsiTreeUtil.isAncestor(startStatement, c, false);
                if (fromStartLine) { // the start line's own call (bookingService.bookTrip(…) on it): that is the way on
                    onTrack.clear();
                    for (PsiCall c : n.getValue()) if (startStatement != null && PsiTreeUtil.isAncestor(startStatement, c, false)) onTrack.add(c);
                }
                int step = fromStartLine ? 0 : onTrack.isEmpty() ? OFF_TRACK : ON_TRACK;
                int total = cost.get(m) + step;
                if (done.contains(t) || cost.containsKey(t) && cost.get(t) <= total) continue;
                // into the end method: every call to it (bookTrip() calls getDestination() on two lines: both)
                List<PsiCall> used = t.equals(e) ? n.getValue() : onTrack.isEmpty() ? List.of(n.getValue().get(0)) : onTrack;
                cost.put(t, total);
                depth.put(t, depth.get(m) + 1);
                came.put(t, new Hop(m, used));
                tracked.put(t, carry(names, used, t));
                queue.add(new Queued(t, total, order++));
            }
        }
        if (!came.containsKey(e)) {
            LOG.info("No way from " + a.getName() + " to " + e.getName() + ": read " + done.size() + " methods "
                    + done.stream().map(PsiMethod::getName).limit(30).toList() + "; calls not resolved " + unresolved
                    + "; outside the project " + outside + "; dumb " + com.intellij.openapi.project.DumbService.isDumb(project));
            if (done.size() <= 1 && !unresolved.isEmpty() && next0Empty(a, startOffset)) {
                return new Result(null, "IntelliJ can't resolve the code of " + from.fileName
                        + " (none of its calls lead anywhere). Is the project set up? Check that its Gradle/Maven sync worked"
                        + " and the code isn't red, then connect again.");
            }
            return new Result(null, "No calls lead from " + a.getName() + "() to " + e.getName() + "() in the project's code"
                    + " (looked " + MAX_DEPTH + " calls deep" + (unresolved.isEmpty() ? "" : "; " + unresolved.size()
                    + " calls couldn't be read: is the project still indexing?") + ").");
        }
        List<List<PsiCall>> chain = new ArrayList<>();
        for (Hop h = came.get(e); h != null; h = came.get(h.from())) chain.add(0, h.calls());
        LinkedHashSet<FlowEntry> out = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        for (List<PsiCall> calls : chain) {
            for (PsiCall call : calls) {
                if (startStatement != null && PsiTreeUtil.isAncestor(startStatement, call, false)) continue; // the start line itself
                FlowEntry entry = entryAt(project, call);
                if (entry != null && seen.add(entry.fileUrl + ":" + entry.line)) out.add(entry);
            }
        }
        int hops = chain.size();
        return new Result(new ArrayList<>(out), out.isEmpty() ? a.getName() + "() calls " + e.getName() + "() directly."
                : "Found the way from " + a.getName() + "() to " + e.getName() + "() (" + hops + (hops == 1 ? " call, " : " calls, ")
                + out.size() + (out.size() == 1 ? " line)." : " lines)."), cost.get(e));
    }

    /**
     * The methods a call may run: what it resolves to; when the IDE can't resolve it (a module or library it can't
     * see, code with errors), the methods of that name and argument count in the class of the object it is made on,
     * else in the project.
     */
    private static List<PsiMethod> targetsOf(Project project, PsiCall call) {
        PsiMethod resolved = call.resolveMethod();
        if (resolved != null) return List.of(resolved);
        List<PsiMethod> out = new ArrayList<>();
        if (!(call instanceof PsiMethodCallExpression mc)) {
            return out;
        }
        for (com.intellij.psi.JavaResolveResult r : mc.getMethodExpression().multiResolve(true)) {
            if (r.getElement() instanceof PsiMethod pm) out.add(pm);
        }
        if (!out.isEmpty()) return out;
        String name = mc.getMethodExpression().getReferenceName();
        if (name == null) return out;
        int args = mc.getArgumentList().getExpressionCount();
        PsiExpression q = mc.getMethodExpression().getQualifierExpression();
        PsiClass cls = q == null ? PsiTreeUtil.getParentOfType(call, PsiClass.class)
                : q.getType() instanceof com.intellij.psi.PsiClassType ct ? ct.resolve() : null;
        if (cls != null) {
            for (PsiMethod x : cls.findMethodsByName(name, true)) if (x.getParameterList().getParametersCount() == args) out.add(x);
        }
        if (out.isEmpty()) {
            for (PsiMethod x : com.intellij.psi.search.PsiShortNamesCache.getInstance(project)
                    .getMethodsByName(name, GlobalSearchScope.projectScope(project))) {
                if (x.getParameterList().getParametersCount() == args) out.add(x);
            }
        }
        return out;
    }

    /** True when none of a method's calls (from an offset on) resolve: the IDE can't read its code. */
    private static boolean next0Empty(PsiMethod m, int from) {
        for (PsiCall c : calls(m, from)) if (c.resolveMethod() != null) return false;
        return true;
    }

    private static String shortText(PsiElement e) {
        String t = e.getText().replaceAll("\\s+", " ");
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }

    /** The method itself and, when it can be overridden, the project's methods that override it. */
    private static List<PsiMethod> candidates(PsiMethod target, GlobalSearchScope scope) {
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

    /** The calls a method makes (method calls and new …()), in order; from an offset on when it is ≥ 0. */
    private static List<PsiCall> calls(PsiMethod m, int from) {
        List<PsiCall> out = new ArrayList<>();
        if (m.getBody() == null) return out;
        m.getBody().accept(new JavaRecursiveElementWalkingVisitor() {
            @Override public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                if (expression.getTextOffset() >= from) out.add(expression);
            }

            @Override public void visitNewExpression(PsiNewExpression expression) {
                super.visitNewExpression(expression);
                if (expression.getTextOffset() >= from) out.add(expression);
            }
        });
        return out;
    }

    /** True when a call (or the statement it is in) uses one of the tracked names. */
    private static boolean touches(PsiCall call, Set<String> names) {
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
    private static Set<String> carry(Set<String> names, List<PsiCall> calls, PsiMethod into) {
        Set<String> in = new LinkedHashSet<>();
        PsiParameter[] params = into.getParameterList().getParameters();
        for (PsiCall call : calls) {
            PsiExpressionList args = call.getArgumentList();
            if (args == null) continue;
            PsiExpression[] exprs = args.getExpressions();
            for (int i = 0; i < exprs.length && i < params.length; i++) {
                for (String n : names) if (referenced(exprs[i]).contains(n)) in.add(params[i].getName());
            }
            // the object the call is made on: when it is tracked, so are its fields of tracked names
            if (call instanceof PsiMethodCallExpression mc && mc.getMethodExpression().getQualifierExpression() != null) {
                for (String n : names) {
                    if (referenced(mc.getMethodExpression().getQualifierExpression()).contains(n)) in.add("this");
                }
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

    private static int lineStart(PsiElement at) {
        PsiStatement st = PsiTreeUtil.getParentOfType(at, PsiStatement.class, false);
        return (st != null ? st : at).getTextRange().getStartOffset();
    }

    /** A flow line for the line a call is on. */
    private static @Nullable FlowEntry entryAt(Project project, PsiCall call) {
        PsiFile file = call.getContainingFile();
        VirtualFile vf = file == null ? null : file.getVirtualFile();
        Document doc = vf == null ? null : FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return null;
        // the line the call is made on: where the called method's name is (travel.getDestination() on the second
        // line of "double hotelCost = hotelService.calculateCost(\n travel.getDestination(), …" is that second line)
        PsiElement name = call instanceof PsiMethodCallExpression mc ? mc.getMethodExpression().getReferenceNameElement() : null;
        int at = name != null ? name.getTextOffset() : call.getTextOffset();
        return FlowCapture.lineEntry(project, doc, doc.getLineNumber(at)); // lines in the flow already count too
    }

    private static String describe(FlowEntry e) {
        return e.fileName + ":" + e.line;
    }

}
