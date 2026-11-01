package com.example.flowtracker.code;

import org.junit.jupiter.api.Test;

import static com.example.flowtracker.Fixtures.calls;
import static com.example.flowtracker.Fixtures.offset;
import static com.example.flowtracker.Fixtures.scope;
import static com.example.flowtracker.Fixtures.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading web files from their text: scopes of lines, and calls (JSP -> Java, JS -> JS). */
class FlowOutlineTest {

    // ---------- scopes ----------

    @Test
    void javaScriptCallbacksAndFunctions() {
        assertEquals("- | addEventListener(\"submit\") callback", scope("travel.js", 9));
        assertEquals("- | addEventListener(\"DOMContentLoaded\") callback", scope("travel.js", 4));
        assertEquals("- | validate()", scope("booking.js", 5));
        assertEquals("- | checkDays()", scope("booking.js", 15));
    }

    @Test
    void jspDeclarationsScriptletsAndMarkup() {
        assertEquals("- | escapeHtml()", scope("search.jsp", 5));
        assertEquals("- | [scriptlet]", scope("search.jsp", 18));
        assertEquals("- | <h1>", scope("search.jsp", 29));
        assertEquals("- | form#searchForm", scope("index.jsp", 10));
        assertEquals("- | form#searchForm", scope("index.jsp", 12)); // an input inside the form
        assertEquals("- | <body>", scope("index.jsp", 8));          // "</head><body><main>"
        assertEquals("- | p#formMessage", scope("index.jsp", 15));
    }

    @Test
    void tagLibraryTagsAndHeader() {
        assertEquals("travel | tag status", scope("travel.tld", 8));
        assertEquals("travel | tag status", scope("travel.tld", 9));
        assertEquals("travel | [taglib]", scope("travel.tld", 6));
    }

    @Test
    void functionNames() {
        assertEquals("loadPackages", FlowOutline.functionName("export async function loadPackages(budget)"));
        assertEquals("onSubmit", FlowOutline.functionName("const onSubmit = async (event) =>"));
        assertEquals("search", FlowOutline.functionName("search(term: string): Promise<void>"));
        assertEquals("find", FlowOutline.functionName("public List<Travel> find(int id) throws IOException"));
        assertNull(FlowOutline.functionName("if (budget < 0)"));
        assertNull(FlowOutline.functionName("} else if (x)"));
        assertNull(FlowOutline.functionName("new Runnable()"));
        assertEquals("map() callback", FlowOutline.callbackName("items.map((x) =>"));
    }

    // ---------- JSP -> Java calls ----------

    private static String jspCalls(int line) {
        String jsp = text("search.jsp");
        return calls(FlowOutline.jspJavaCalls(jsp, offset(jsp, line)));
    }

    @Test
    void scriptletCallsWithArgumentCounts() {
        String l21 = jspCalls(21);
        assertTrue(l21.contains("new DestinationService/0") && l21.contains("search/3"), l21);
        assertTrue(l21.contains("getParameter/1") && l21.contains("parseDouble/1"), "string arguments count\n" + l21);
    }

    @Test
    void expressionsInMarkupAndDeclarations() {
        String l33 = jspCalls(33);
        assertTrue(l33.contains("getDestination/0") && l33.contains("escapeHtml/1") && l33.contains("getPackagePrice/0"), l33);
        String l5 = jspCalls(5);
        assertFalse(l5.contains("escapeHtml"), "a declaration is not a call: " + l5);
        assertTrue(l5.contains("replace/2"), l5);
        assertEquals("", jspCalls(29), "plain markup has no calls");
    }

    @Test
    void scriptsAndStringsAreNotJavaCalls() {
        assertFalse(calls(FlowOutline.jspJavaCalls("<script>search(1, 2, 3);</script>\n", 0)).contains("search"));
        assertFalse(calls(FlowOutline.jspJavaCalls("<% String s = \"x.search(a, b, c)\"; %>\n", 0)).contains("search"));
    }

    // ---------- JS -> JS calls ----------

    private static String scriptCalls(int line) {
        String js = text("booking.js");
        return calls(FlowOutline.scriptCalls(js, offset(js, line)));
    }

    @Test
    void callsInTheEnclosingFunction() {
        String inValidate = scriptCalls(6);
        assertTrue(inValidate.contains("checkBudget/1") && inValidate.contains("checkDays/1")
                && inValidate.contains("showError/1"), "the whole function body is read\n" + inValidate);
        assertTrue(scriptCalls(23).contains("validate/1"), "the submit callback calls validate()");
    }

    @Test
    void declarationsStringsAndCommentsAreNotCalls() {
        String inBudget = scriptCalls(11);
        assertFalse(inBudget.contains("checkDays"), "\"checkDays(1)\" in a string / comment: " + inBudget);
        assertFalse(scriptCalls(5).contains("validate/"), "the declaration itself is not a call");
    }

    /** The class and method of a 1-based line of an inline source text. */
    private static String at(String fileName, String text, int line) {
        int offset = 0;
        for (int l = 1; l < line; l++) offset = text.indexOf('\n', offset) + 1;
        while (offset < text.length() && (text.charAt(offset) == ' ' || text.charAt(offset) == '\t')) offset++;
        FlowOutline.Scope s = FlowOutline.locate(text, offset, fileName);
        return s.className() + " | " + s.methodName();
    }

    @Test
    void otherBraceLanguages() {
        assertEquals("BookingService | Book()", at("BookingService.cs", """
                namespace Travel {
                  public class BookingService {
                    public async Task<int> Book(string name) {
                      var id = Save(name);
                      return id;
                    }
                  }
                }
                """, 4), "C#");
        assertEquals("- | Handle()", at("server.go", """
                package main
                func (s *Server) Handle(w http.ResponseWriter, r *http.Request) {
                    s.count++
                }
                """, 3), "Go, a method with a receiver");
        assertEquals("Booking | total()", at("booking.rs", """
                impl Booking {
                    pub fn total(&self) -> f64 {
                        self.price * 2.0
                    }
                }
                """, 3), "Rust");
        assertEquals("Cart | add()", at("cart.cpp", """
                class Cart {
                  void add(int item) const {
                    items.push_back(item);
                  }
                };
                """, 3), "C++");
        assertEquals("BookingService | bookTrip()", at("BookingService.class", """
                public class BookingService {
                    public void bookTrip(String name, int days) {
                        this.validate(name);
                    }
                }
                """, 3), "the decompiled Java of a .class file");
    }

    @Test
    void indentedLanguages() {
        assertEquals("BookingService | book()", at("booking.py", """
                class BookingService:
                    def book(self, name):
                        if not name:
                            raise ValueError("name")
                        return save(name)
                """, 4), "Python");
        assertEquals("- | main()", at("app.py", """
                def main():
                    run()
                """, 2), "a Python function outside a class");
        assertEquals("Cart | add()", at("cart.rb", """
                class Cart
                  def add(item)
                    @items << item
                  end
                end
                """, 3), "Ruby");
        assertEquals("go", FlowLang.fence("server.go"));
        assertEquals("java", FlowLang.fence("BookingService.class"));
        assertTrue(FlowLang.isSupportedName("models.py"));
    }
}
