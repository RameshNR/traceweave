package com.example.flowtracker.compat;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.util.ThrowableComputable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * IntelliJ Platform APIs that differ between the IDE versions TraceWeave supports (2023.1 and later). The plugin is
 * compiled against 2023.1; where a newer IDE replaced an API, the newer one is looked up when the plugin starts and
 * used when it is there, so every IDE gets the call it expects (and the Plugin Verifier sees no deprecated use).
 */
public final class FlowCompat {
    private FlowCompat() {}

    /** {@code ReadAction.computeBlocking} (IDEs after 2025.2), or null when the IDE only has {@code ReadAction.compute}. */
    private static final MethodHandle COMPUTE_BLOCKING = find(ReadAction.class, "computeBlocking",
            MethodType.methodType(Object.class, ThrowableComputable.class));

    /** {@code ReadAction.compute}: what IDEs before {@code computeBlocking} have (deprecated once it came). */
    private static final MethodHandle COMPUTE = find(ReadAction.class, "compute",
            MethodType.methodType(Object.class, ThrowableComputable.class));

    /**
     * A class's JVM name ("com.shop.Order$Line"): its qualified name, with "$" before each nested class's name; null
     * for an anonymous or local class. What {@code ClassUtil.getJVMClassName} gives, without that method (deprecated
     * from IntelliJ IDEA 2025.2; its replacement isn't in 2023.1).
     */
    public static @org.jetbrains.annotations.Nullable String jvmName(@org.jetbrains.annotations.Nullable com.intellij.psi.PsiClass c) {
        if (c == null || c instanceof com.intellij.psi.PsiAnonymousClass || c.getName() == null) return null;
        com.intellij.psi.PsiClass outer = c.getContainingClass();
        if (outer != null) {
            String o = jvmName(outer);
            return o == null ? null : o + "$" + c.getName();
        }
        if (!(c.getParent() instanceof com.intellij.psi.PsiFile)) return null; // a local class
        return c.getQualifiedName();
    }

    /** Runs {@code action} in a read action, waiting for it, and returns its result. */
    @SuppressWarnings("unchecked")
    public static <T> T read(ThrowableComputable<T, RuntimeException> action) {
        try {
            return (T) (COMPUTE_BLOCKING != null ? COMPUTE_BLOCKING : COMPUTE).invoke(action);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * {@code FileSaverDescriptor(title, description, extension)} (2025.1 and later), else the
     * {@code (title, description, String... extensions)} one 2023.1 has (deprecated in the newer IDEs).
     */
    private static final MethodHandle SAVER_ONE_EXTENSION = constructor(FileSaverDescriptor.class,
            MethodType.methodType(void.class, String.class, String.class, String.class));
    private static final MethodHandle SAVER_EXTENSIONS = constructor(FileSaverDescriptor.class,
            MethodType.methodType(void.class, String.class, String.class, String[].class));

    /** A save dialog's descriptor for files with one extension (e.g. "json"). */
    public static FileSaverDescriptor saverDescriptor(String title, String description, String extension) {
        try {
            if (SAVER_ONE_EXTENSION != null) return (FileSaverDescriptor) SAVER_ONE_EXTENSION.invoke(title, description, extension);
            return (FileSaverDescriptor) SAVER_EXTENSIONS.invoke(title, description, new String[]{extension});
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    private static MethodHandle find(Class<?> owner, String name, MethodType type) {
        try {
            return MethodHandles.publicLookup().findStatic(owner, name, type);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            return null;
        }
    }

    private static MethodHandle constructor(Class<?> owner, MethodType type) {
        try {
            return MethodHandles.publicLookup().findConstructor(owner, type);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            return null;
        }
    }
}
