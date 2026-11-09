package org.scenarioservice.gherkin;

import java.util.List;

/**
 * A Gherkin Rule with its Background and scenarios, in text order. {@link #toString()} shows no content (P16).
 */
public record ParsedRule(String keyword, String name, String description, int line, List<String> tags,
                         List<ParsedScenario> children) implements ParsedChild {

    public ParsedRule {
        tags = List.copyOf(tags);
        children = List.copyOf(children);
    }

    @Override
    public String toString() {
        return "ParsedRule[line=" + line + ", children=" + children.size() + "]";
    }
}
