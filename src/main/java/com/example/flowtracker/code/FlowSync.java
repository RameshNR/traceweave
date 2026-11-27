package com.example.flowtracker.code;

import com.example.flowtracker.copy.FlowFormat;
import com.example.flowtracker.model.FlowEntry;
import com.example.flowtracker.model.FlowStore;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.JavaResolveResult;
import com.intellij.psi.PsiCallExpression;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiMethodReferenceExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UCallableReferenceExpression;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UMethod;
import org.jetbrains.uast.UastCallKind;
import org.jetbrains.uast.UastContextKt;
import org.jetbrains.uast.visitor.AbstractUastVisitor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * "Sync Files": puts the saved lines in call order. A method that calls another tracked method comes first,
 * and the called method's lines follow right after the line that calls it, e.g.
 * Main.main() line 12 -> BookingService.bookTrip() lines 21, 27 -> Main.main() line 14.
 * Lines inside one method are kept in line-number order.
 */
public final class FlowSync {
    private FlowSync() {}

    /** A call from one tracked method to another, at a 1-based line of the caller. */
    private record Call(Object callee, int line) {}

    /**
     * The calls between a flow's saved methods: {@link #link}s (each caller's calls in source order) and, for
     * each link, the 1-based line of the caller the call is on (used to nest the callee in the Original Log).
     */
    public record Calls(Set<String> links, Map<String, Integer> lines) {
        public static final Calls NONE = new Calls(Set.of(), Map.of());
    }

    /**
     * The calls plus those connected by hand ({@link FlowEntry#calls}): each manual connection is a link from
     * the line's method to the chosen method, at that line. Connections to methods no longer in the flow are
     * skipped.
     */
    public static Calls withManual(Calls calls, List<FlowEntry> entries) {
        Set<String> keys = new HashSet<>();
        boolean any = false;
        for (FlowEntry e : entries) {
            keys.add(methodKey(e));
            any |= !e.calls.isEmpty();
        }
        if (!any) return calls;
        Set<String> links = new LinkedHashSet<>(calls.links());
        Map<String, Integer> lines = new HashMap<>(calls.lines());
        for (FlowEntry e : entries) {
            for (String callee : e.calls) {
                String from = methodKey(e);
                if (!keys.contains(callee) || callee.equals(from)) continue;
                String link = link(from, callee);
                links.add(link);
                lines.merge(link, e.line, Math::min);
            }
        }
        return new Calls(links, lines);
    }

    /** Result of one analysis: lines in call order, plus the calls between tracked methods. */
    private record Analysis(List<FlowEntry> order, Calls calls) {}

    /** Key of the method a line is in, as used by {@link #link}: file URL + method label. */
    public static String methodKey(FlowEntry e) {
        return e.fileUrl + "#" + FlowFormat.methodLabel(e);
    }

    /** A "caller calls callee" link between two method keys. */
    public static String link(String callerKey, String calleeKey) {
        return callerKey + ARROW + calleeKey;
    }

    private static final String ARROW = " -> ";

    /** The caller and callee keys of a {@link #link}, or null if it isn't one. */
    public static String[] parseLink(String link) {
        int i = link.indexOf(ARROW);
        return i < 0 ? null : new String[]{link.substring(0, i), link.substring(i + ARROW.length())};
    }

    /**
     * Analyses several flows in the background (after indexing) and hands the call links of each flow, in
     * the same order, to {@code done} on the UI thread. Used by Copy All Flows: only the active flow's links
     * are kept up to date in the store.
     */
    public static void callsFor(Project project, List<List<FlowEntry>> flows, Consumer<List<Calls>> done) {
        ReadAction.nonBlocking(() -> flows.stream()
                        .map(f -> f.size() < 2 ? Calls.NONE : compute(project, f).calls())
                        .toList())
                .inSmartMode(project)
                .expireWith(project)
                .finishOnUiThread(ModalityState.defaultModalityState(), done::accept)
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    /**
     * Analyses which saved methods call which (kept in the store for the Markdown flow diagram) and,
     * if Sync Files is on, re-sorts the lines into call order. The analysis runs in the background
     * (after indexing has finished); results are applied on the UI thread, unless the lines changed in
     * the meantime (a newer call then takes over).
     */
    public static void apply(Project project) {
        FlowStore store = FlowStore.get(project);
        List<FlowEntry> snapshot = store.getEntries();
        if (snapshot.size() < 2) {
            store.setCalls(Calls.NONE);
            return;
        }
        ReadAction.nonBlocking(() -> compute(project, snapshot))
                .inSmartMode(project)
                .expireWith(project)
                .coalesceBy(store)
                .finishOnUiThread(ModalityState.defaultModalityState(), result -> {
                    List<FlowEntry> now = store.getEntries();
                    if (!now.equals(snapshot)) return;
                    store.setCalls(result.calls());
                    FlowCallCheck.afterSync(project, withManual(result.calls(), now).links()); // expected calls still there?
                    if (store.isSyncFiles() && !result.order().equals(now)) store.setOrder(result.order());
                })
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    private static Analysis compute(Project project, List<FlowEntry> entries) {
        // Group lines by the method they are in (lines outside methods are grouped by their label).
        Map<Object, List<FlowEntry>> groups = new LinkedHashMap<>();
        for (FlowEntry e : entries) {
            PsiMethod m = methodAt(project, e);
            Object key = m != null ? m : e.fileUrl + "#" + e.className + "#" + e.methodName;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }
        for (List<FlowEntry> g : groups.values()) g.sort(Comparator.comparingInt(e -> e.line));

        // Which tracked methods each tracked method calls, in the order the calls appear.
        Map<PsiMethod, Set<PsiMethod>> supers = new HashMap<>();
        for (Object key : groups.keySet()) {
            if (key instanceof PsiMethod m) supers.put(m, allSupers(m));
        }
        Map<Object, List<Call>> calls = new HashMap<>();
        Set<Object> called = new HashSet<>();
        for (Object key : groups.keySet()) {
            List<Call> found;
            if (key instanceof PsiMethod caller) found = callsFrom(caller, supers);
            else if (FlowLang.kind(groups.get(key).get(0).fileName) == FlowLang.Kind.JSP) found = jspCalls(groups.get(key), supers);
            else if (FlowLang.kind(groups.get(key).get(0).fileName) == FlowLang.Kind.SCRIPT) found = scriptCalls(key, groups);
            else continue;
            calls.put(key, new ArrayList<>(found)); // the finders may return read-only lists; connections are added below
            for (Call c : found) called.add(c.callee);
        }

        // Calls connected by hand, from the line's method to the chosen method, at that line.
        Map<String, Object> byKey = new HashMap<>();
        for (Map.Entry<Object, List<FlowEntry>> g : groups.entrySet()) byKey.putIfAbsent(methodKey(g.getValue().get(0)), g.getKey());
        for (Map.Entry<Object, List<FlowEntry>> g : groups.entrySet()) {
            for (FlowEntry e : g.getValue()) {
                for (String target : e.calls) {
                    Object callee = byKey.get(target);
                    if (callee == null || callee.equals(g.getKey())) continue;
                    List<Call> list = calls.computeIfAbsent(g.getKey(), k -> new ArrayList<>());
                    if (list.stream().noneMatch(c -> c.callee.equals(callee))) list.add(new Call(callee, e.line));
                    called.add(callee);
                }
            }
        }
        for (List<Call> list : calls.values()) list.sort(Comparator.comparingInt(Call::line));

        List<FlowEntry> out = new ArrayList<>(entries.size());
        Set<Object> visited = new HashSet<>();
        // Entry points first (methods no other tracked method calls), in their current order.
        for (Object key : groups.keySet()) {
            if (!called.contains(key)) emit(key, groups, calls, visited, out);
        }
        // Whatever is left is only reachable through a cycle.
        for (Object key : groups.keySet()) emit(key, groups, calls, visited, out);

        Set<String> links = new LinkedHashSet<>();
        Map<String, Integer> lines = new HashMap<>();
        for (Map.Entry<Object, List<Call>> c : calls.entrySet()) {
            String from = methodKey(groups.get(c.getKey()).get(0));
            for (Call call : c.getValue()) {
                String link = link(from, methodKey(groups.get(call.callee).get(0)));
                links.add(link);
                lines.putIfAbsent(link, call.line);
            }
        }
        return new Analysis(out, new Calls(links, lines));
    }

    /** Adds a method's lines, inserting each called method right after the line that calls it. */
    private static void emit(Object key, Map<Object, List<FlowEntry>> groups, Map<Object, List<Call>> calls,
                             Set<Object> visited, List<FlowEntry> out) {
        if (!visited.add(key)) return;
        List<Call> pending = new ArrayList<>(calls.getOrDefault(key, List.of()));
        for (FlowEntry e : groups.get(key)) {
            // Calls made before this line happen first.
            while (!pending.isEmpty() && pending.get(0).line < e.line) {
                emit(pending.remove(0).callee, groups, calls, visited, out);
            }
            out.add(e);
        }
        for (Call c : pending) emit(c.callee, groups, calls, visited, out);
    }

    /** Calls (and method references) inside the caller that reach one of the tracked methods. */
    private static List<Call> callsFrom(PsiMethod caller, Map<PsiMethod, Set<PsiMethod>> supers) {
        PsiFile source = caller.getNavigationElement().getContainingFile();
        if (source != null && !FlowLang.isJava(source.getName())) {
            return uCallsFrom(caller, supers); // Kotlin: walk the UAST tree instead of Java PSI
        }
        PsiFile file = caller.getContainingFile();
        Document doc = file == null ? null
                : PsiDocumentManager.getInstance(caller.getProject()).getDocument(file);

        List<Call> out = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        List<PsiElement> sites = new ArrayList<>(PsiTreeUtil.findChildrenOfType(caller, PsiCallExpression.class));
        sites.addAll(PsiTreeUtil.findChildrenOfType(caller, PsiMethodReferenceExpression.class));
        sites.sort(Comparator.comparingInt(PsiElement::getTextOffset));

        for (PsiElement site : sites) {
            PsiMethod callee = calleeOf(site, supers);
            if (callee == null || callee == caller || !seen.add(callee)) continue;
            int line = doc == null ? 0 : doc.getLineNumber(site.getTextOffset()) + 1;
            out.add(new Call(callee, line));
        }
        return out;
    }

    /**
     * Calls from a JSP's Java code (the scriptlets, declarations and expressions its saved lines are in) to
     * the saved Java methods, matched by name and argument count, in the order they appear in the page.
     */
    private static List<Call> jspCalls(List<FlowEntry> lines, Map<PsiMethod, Set<PsiMethod>> supers) {
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(lines.get(0).fileUrl);
        Document doc = vf == null || !vf.isValid() ? null : FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return List.of();
        CharSequence text = doc.getCharsSequence();

        Map<Integer, FlowOutline.TextCall> byOffset = new java.util.TreeMap<>(); // one block can hold several saved lines
        for (FlowEntry e : lines) {
            if (e.line < 1 || e.line > doc.getLineCount()) continue;
            int start = doc.getLineStartOffset(e.line - 1);
            String lineText = text.subSequence(start, doc.getLineEndOffset(e.line - 1)).toString();
            int offset = start + (lineText.length() - lineText.stripLeading().length());
            for (FlowOutline.TextCall c : FlowOutline.jspJavaCalls(text, offset)) byOffset.putIfAbsent(c.offset(), c);
        }

        List<Call> out = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        for (FlowOutline.TextCall c : byOffset.values()) {
            PsiMethod callee = byName(c.name(), c.args(), c.constructor(), supers);
            if (callee != null && seen.add(callee)) out.add(new Call(callee, doc.getLineNumber(c.offset()) + 1));
        }
        return out;
    }

    private static final java.util.regex.Pattern SCRIPT_FUNCTION = java.util.regex.Pattern.compile("([A-Za-z_$][\\w$]*)\\(\\)");

    /**
     * Calls from a script function (the function its saved lines are in) to other saved script functions,
     * matched by name. A name saved in several files links to the one in the same file, else to none.
     */
    private static List<Call> scriptCalls(Object key, Map<Object, List<FlowEntry>> groups) {
        // Saved script functions by name ("validate()" -> validate), to match calls against.
        Map<String, List<Object>> byName = new HashMap<>();
        for (Map.Entry<Object, List<FlowEntry>> g : groups.entrySet()) {
            FlowEntry first = g.getValue().get(0);
            if (FlowLang.kind(first.fileName) != FlowLang.Kind.SCRIPT) continue;
            java.util.regex.Matcher m = SCRIPT_FUNCTION.matcher(first.methodName);
            if (m.matches()) byName.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(g.getKey());
        }
        List<FlowEntry> lines = groups.get(key);
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(lines.get(0).fileUrl);
        Document doc = vf == null || !vf.isValid() ? null : FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null || byName.isEmpty()) return List.of();
        CharSequence text = doc.getCharsSequence();

        Map<Integer, FlowOutline.TextCall> byOffset = new java.util.TreeMap<>();
        for (FlowEntry e : lines) {
            if (e.line < 1 || e.line > doc.getLineCount()) continue;
            int start = doc.getLineStartOffset(e.line - 1);
            String lineText = text.subSequence(start, doc.getLineEndOffset(e.line - 1)).toString();
            int offset = start + (lineText.length() - lineText.stripLeading().length());
            for (FlowOutline.TextCall c : FlowOutline.scriptCalls(text, offset)) byOffset.putIfAbsent(c.offset(), c);
        }

        List<Call> out = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        String file = lines.get(0).fileUrl;
        for (FlowOutline.TextCall c : byOffset.values()) {
            List<Object> candidates = byName.getOrDefault(c.name(), List.of());
            Object callee = candidates.size() == 1 ? candidates.get(0) : null;
            if (callee == null && candidates.size() > 1) {
                List<Object> local = candidates.stream().filter(k -> groups.get(k).get(0).fileUrl.equals(file)).toList();
                if (local.size() == 1) callee = local.get(0);
            }
            if (callee != null && !callee.equals(key) && seen.add(callee)) {
                out.add(new Call(callee, doc.getLineNumber(c.offset()) + 1));
            }
        }
        return out;
    }

    /** Kotlin version of {@link #callsFrom}: the same search over UAST call and callable-reference nodes. */
    private static List<Call> uCallsFrom(PsiMethod caller, Map<PsiMethod, Set<PsiMethod>> supers) {
        UMethod method = UastContextKt.toUElement(caller, UMethod.class);
        if (method == null) return List.of();
        List<UElement> sites = new ArrayList<>();
        method.accept(new AbstractUastVisitor() {
            @Override
            public boolean visitCallExpression(@NotNull UCallExpression node) {
                sites.add(node);
                return false; // keep visiting the arguments: they can hold calls too
            }

            @Override
            public boolean visitCallableReferenceExpression(@NotNull UCallableReferenceExpression node) {
                sites.add(node);
                return false;
            }
        });

        record Site(int offset, PsiMethod callee, int line) {}
        List<Site> found = new ArrayList<>();
        for (UElement site : sites) {
            PsiMethod callee = uCalleeOf(site, supers);
            PsiElement src = site.getSourcePsi();
            if (callee == null || callee.equals(caller) || src == null) continue;
            PsiFile file = src.getContainingFile();
            Document doc = file == null ? null : PsiDocumentManager.getInstance(src.getProject()).getDocument(file);
            int offset = src.getTextOffset();
            found.add(new Site(offset, callee, doc == null ? 0 : doc.getLineNumber(offset) + 1));
        }
        found.sort(Comparator.comparingInt(Site::offset));

        List<Call> out = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        for (Site s : found) {
            if (seen.add(s.callee)) out.add(new Call(s.callee, s.line));
        }
        return out;
    }

    private static @Nullable PsiMethod uCalleeOf(UElement site, Map<PsiMethod, Set<PsiMethod>> supers) {
        if (site instanceof UCallableReferenceExpression ref) {
            if (ref.resolve() instanceof PsiMethod m) {
                PsiMethod match = trackedMatch(m, supers);
                if (match != null) return match;
            }
            return byName(ref.getCallableName(), -1, false, supers);
        }
        UCallExpression call = (UCallExpression) site;
        PsiMethod resolved = call.resolve();
        if (resolved != null) {
            PsiMethod match = trackedMatch(resolved, supers);
            if (match != null) return match;
        }
        int args = call.getValueArgumentCount();
        if (call.getKind() == UastCallKind.CONSTRUCTOR_CALL) {
            String type = call.getClassReference() == null ? null : call.getClassReference().getResolvedName();
            return byName(type, args, true, supers);
        }
        return byName(call.getMethodName(), args, false, supers);
    }

    /** Every method the given one overrides or implements, at any depth. */
    private static Set<PsiMethod> allSupers(PsiMethod m) {
        Set<PsiMethod> out = new HashSet<>();
        List<PsiMethod> todo = new ArrayList<>(List.of(m.findSuperMethods()));
        while (!todo.isEmpty()) {
            PsiMethod s = todo.remove(todo.size() - 1);
            if (out.add(s)) todo.addAll(List.of(s.findSuperMethods()));
        }
        return out;
    }

    /**
     * The tracked method a call site reaches. Resolving can fail when the project has problems
     * (for example no JDK configured, so argument types are unknown), so this falls back to a looser
     * resolve, and finally to a tracked method with the same name and number of arguments.
     */
    private static @Nullable PsiMethod calleeOf(PsiElement site, Map<PsiMethod, Set<PsiMethod>> supers) {
        if (site instanceof PsiMethodReferenceExpression ref) {
            if (ref.resolve() instanceof PsiMethod m) return trackedMatch(m, supers);
            return byName(ref.getReferenceName(), -1, false, supers);
        }
        PsiCallExpression call = (PsiCallExpression) site;
        PsiMethod resolved = call.resolveMethod();
        if (resolved == null && call.resolveMethodGenerics().getElement() instanceof PsiMethod m) resolved = m;
        if (resolved == null && call instanceof PsiMethodCallExpression mc) {
            for (JavaResolveResult r : mc.getMethodExpression().multiResolve(true)) {
                if (r.getElement() instanceof PsiMethod m) {
                    resolved = m;
                    break;
                }
            }
        }
        if (resolved != null) {
            PsiMethod match = trackedMatch(resolved, supers);
            if (match != null) return match;
        }

        int args = call.getArgumentList() == null ? -1 : call.getArgumentList().getExpressionCount();
        if (call instanceof PsiMethodCallExpression mc) {
            return byName(mc.getMethodExpression().getReferenceName(), args, false, supers);
        }
        if (call instanceof PsiNewExpression ne && ne.getClassReference() != null) {
            return byName(ne.getClassReference().getReferenceName(), args, true, supers);
        }
        return null;
    }

    /** The only tracked method with this name (and argument count, unless -1), or null if none or several. */
    private static @Nullable PsiMethod byName(@Nullable String name, int args, boolean constructor,
                                              Map<PsiMethod, Set<PsiMethod>> supers) {
        if (name == null) return null;
        PsiMethod found = null;
        for (PsiMethod m : supers.keySet()) {
            if (m.isConstructor() != constructor || !name.equals(m.getName())) continue;
            if (args >= 0 && !m.isVarArgs() && m.getParameterList().getParametersCount() != args) continue;
            if (found != null) return null; // ambiguous: better no link than a wrong one
            found = m;
        }
        return found;
    }

    /** The tracked method a call resolves to: the method itself, or an implementation of it. */
    private static @Nullable PsiMethod trackedMatch(PsiMethod resolved, Map<PsiMethod, Set<PsiMethod>> supers) {
        if (supers.containsKey(resolved)) return resolved;
        for (Map.Entry<PsiMethod, Set<PsiMethod>> t : supers.entrySet()) {
            if (t.getValue().contains(resolved)) return t.getKey(); // call through an interface / base class
        }
        return null;
    }

    private static @Nullable PsiMethod methodAt(Project project, FlowEntry e) {
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(e.fileUrl);
        if (vf == null || !vf.isValid()) return null;
        PsiFile psi = PsiManager.getInstance(project).findFile(vf);
        if (psi == null) return null;
        Document doc = PsiDocumentManager.getInstance(project).getDocument(psi);
        if (doc == null || e.line < 1 || e.line > doc.getLineCount()) return null;
        int start = doc.getLineStartOffset(e.line - 1);
        int end = doc.getLineEndOffset(e.line - 1);
        String text = doc.getCharsSequence().subSequence(start, end).toString();
        int offset = start + (text.length() - text.stripLeading().length());
        PsiElement el = psi.findElementAt(offset);
        if (FlowLang.usesJavaPsi(e.fileName)) return PsiTreeUtil.getParentOfType(el, PsiMethod.class, false);
        if (!FlowLang.isKotlin(e.fileName)) return null; // web and config files: no calls to follow
        UMethod m = FlowLang.uParent(FlowLang.uElementAt(el), UMethod.class);
        return m == null ? null : m.getJavaPsi();
    }
}
