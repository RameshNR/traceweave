package com.example.flowtracker.debug;

import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XValueChildrenList;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The debugger's node made at run time: calls go to the object's methods of the same name and parameters. */
class FlowValueReaderNodeTest {

    @Test
    void callsGoToTheMethodsOfTheSameName() {
        List<String> calls = new ArrayList<>();
        XCompositeNode node = FlowValueReader.node(new Object() {
            public void addChildren(XValueChildrenList children, boolean last) { calls.add("addChildren " + children.size() + " " + last); }
            public void tooManyChildren(int remaining) { calls.add("tooManyChildren " + remaining); }
            public void tooManyChildren(int remaining, Runnable more) { calls.add("tooManyChildren " + remaining + " +more"); }
            public void setErrorMessage(String message) { calls.add("error " + message); }
            public boolean isObsolete() { return true; }
        });
        node.addChildren(XValueChildrenList.EMPTY, true);
        node.tooManyChildren(3, () -> {});
        node.setErrorMessage("gone");
        assertTrue(node.isObsolete());
        assertEquals(List.of("addChildren 0 true", "tooManyChildren 3 +more", "error gone"), calls);
    }

    @Test
    void methodsTheObjectHasNotDoNothing() {
        XCompositeNode node = FlowValueReader.node(new Object());
        node.setAlreadySorted(true); // not there: nothing happens
        assertFalse(node.isObsolete(), "the interface's default");
        XCompositeNode other = FlowValueReader.node(new Object());
        assertEquals(node, node);
        assertNotEquals(node, other);
    }
}
