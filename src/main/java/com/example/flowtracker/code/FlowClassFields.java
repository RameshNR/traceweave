package com.example.flowtracker.code;

import com.example.flowtracker.compat.FlowCompat;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The fields a class has in the code (to add one to a saved debug value): its own, then those it inherits from its
 * parent classes (nearest first), each with its type and the class it is declared in.
 */
public final class FlowClassFields {
    private FlowClassFields() {}

    /**
     * @param owner     the class it is declared in
     * @param inherited true when it comes from a parent class
     */
    public record Field(String name, String type, String owner, boolean inherited, boolean isStatic) {}

    /**
     * The fields of the class the debugger named (e.g. "HotelService", "com.travel.HotelService" or
     * "HotelService$Inner"); empty when the class isn't found or the IDE is still indexing.
     */
    public static List<Field> of(Project project, String typeName) {
        if (typeName == null || typeName.isBlank() || DumbService.isDumb(project)) return List.of();
        return FlowCompat.read(() -> {
            PsiClass cls = find(project, typeName.strip());
            List<Field> out = new ArrayList<>();
            if (cls == null) return out;
            Set<String> seen = new HashSet<>();
            boolean inherited = false;
            for (PsiClass c = cls; c != null && !"java.lang.Object".equals(c.getQualifiedName()); c = c.getSuperClass()) {
                for (PsiField f : c.getFields()) {
                    if (!seen.add(f.getName())) continue; // hidden by a field of the same name further down
                    out.add(new Field(f.getName(), f.getType().getPresentableText(), String.valueOf(c.getName()), inherited,
                            f.hasModifierProperty(PsiModifier.STATIC)));
                }
                inherited = true;
            }
            return out;
        });
    }

    private static PsiClass find(Project project, String typeName) {
        // "HotelService@1141", "{HotelService@1141}", "java.util.HashMap<K, V>", "Item[]" → the class name
        String name = typeName.replaceAll("[{}]", "").replaceAll("@\\d+.*", "").replaceAll("<.*", "")
                .replaceAll("\\[]", "").strip().replace('$', '.');
        GlobalSearchScope all = GlobalSearchScope.allScope(project);
        if (name.contains(".")) {
            PsiClass c = JavaPsiFacade.getInstance(project).findClass(name, all);
            if (c != null) return c;
            name = name.substring(name.lastIndexOf('.') + 1);
        }
        PsiClass[] found = PsiShortNamesCache.getInstance(project).getClassesByName(name, GlobalSearchScope.projectScope(project));
        if (found.length == 0) found = PsiShortNamesCache.getInstance(project).getClassesByName(name, all);
        return found.length == 0 ? null : found[0];
    }
}
