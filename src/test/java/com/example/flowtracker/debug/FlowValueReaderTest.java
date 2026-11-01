package com.example.flowtracker.debug;

import com.example.flowtracker.model.FlowValue;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XValue;
import com.intellij.xdebugger.frame.XValueChildrenList;
import com.intellij.xdebugger.frame.XValueNode;
import com.intellij.xdebugger.frame.XValuePlace;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Reading values the way debuggers answer: late, in parts, a placeholder first. */
class FlowValueReaderTest {

    /**
     * A value like a Java debugger's map: it first answers with a placeholder without children, and 150 ms later with
     * its real value and children (which come in two parts).
     */
    private static final class SlowMap extends XValue {
        private final List<String[]> entries;

        SlowMap(String[]... entries) { this.entries = List.of(entries); }

        @Override
        public void computePresentation(@NotNull XValueNode node, @NotNull XValuePlace place) {
            node.setPresentation(null, null, "Collecting data…", false);
            later(150, () -> node.setPresentation(null, "HashMap", "size = " + entries.size(), true));
        }

        @Override
        public void computeChildren(@NotNull XCompositeNode node) {
            later(50, () -> {
                XValueChildrenList first = new XValueChildrenList();
                first.add(entries.get(0)[0], new Leaf(entries.get(0)[1]));
                node.addChildren(first, false);
                later(80, () -> {
                    XValueChildrenList rest = new XValueChildrenList();
                    for (String[] e : entries.subList(1, entries.size())) rest.add(e[0], new Leaf(e[1]));
                    node.addChildren(rest, true);
                });
            });
        }
    }

    private static final class Leaf extends XValue {
        private final String value;

        Leaf(String value) { this.value = value; }

        @Override
        public void computePresentation(@NotNull XValueNode node, @NotNull XValuePlace place) {
            later(30, () -> node.setPresentation(null, "Integer", value, false));
        }
    }

    private static void later(long ms, Runnable r) {
        CompletableFuture.delayedExecutor(ms, TimeUnit.MILLISECONDS).execute(r);
    }

    @Test
    void waitsForTheRealValueAfterAPlaceholderAndForAllChildren() throws Exception {
        SlowMap rooms = new SlowMap(new String[]{"\"ooty\"", "12"}, new String[]{"\"USA\"", "120"}, new String[]{"\"goa\"", "9"});
        CompletableFuture<FlowValue> read = new CompletableFuture<>();
        FlowValueReader.read("availableRooms", rooms, FlowValueReader.DEPTH, read::complete);
        FlowValue v = read.get(10, TimeUnit.SECONDS);
        assertEquals("size = 3", v.value, "not the placeholder");
        assertEquals("HashMap", v.type);
        assertEquals(List.of("\"ooty\"", "\"USA\"", "\"goa\""), v.children.stream().map(c -> c.name).toList());
        assertEquals("120", v.children.get(1).value);
    }
}
