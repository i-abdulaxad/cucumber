package org.scenarioservice.gherkin;

import java.util.ArrayList;
import java.util.List;

/**
 * A parsed Feature (D8): metadata, its own tags (each once, in text order) and its children in text order. Names,
 * descriptions and keywords are never {@code null} ({@code ""} when absent, D19 #7). {@link #toString()} shows no
 * content (P16).
 */
public record ParsedFeature(String language, String keyword, String name, String description, int line,
                            List<String> tags, List<ParsedChild> children) {

    public ParsedFeature {
        tags = List.copyOf(tags);
        children = List.copyOf(children);
    }

    /**
     * Every Background, Scenario and Scenario Outline, Rules' included, in text order.
     */
    public List<ParsedScenario> scenariosInTextOrder() {
        List<ParsedScenario> scenarios = new ArrayList<>();
        for (ParsedChild child : children) {
            if (child instanceof ParsedScenario scenario) {
                scenarios.add(scenario);
            } else if (child instanceof ParsedRule rule) {
                scenarios.addAll(rule.children());
            }
        }
        return scenarios;
    }

    /**
     * Every Examples row of every outline, in text order.
     */
    public List<ParsedRow> rowsInTextOrder() {
        return scenariosInTextOrder().stream().flatMap(scenario -> scenario.examples().stream())
                .flatMap(examples -> examples.rows().stream()).toList();
    }

    /**
     * D21 #2: the elements a store of this feature writes: every Rule, Background, Scenario, Scenario Outline, Examples
     * block, step and example row, plus each tag assignment (each tag once per element). Data-table cells and
     * doc-string text don't count.
     */
    public int elementCount() {
        int count = tags.size();
        for (ParsedChild child : children) {
            if (child instanceof ParsedRule rule) {
                count += 1 + rule.tags().size();
            }
        }
        for (ParsedScenario scenario : scenariosInTextOrder()) {
            count += 1 + scenario.tags().size() + scenario.steps().size();
            for (ParsedExamples examples : scenario.examples()) {
                count += 1 + examples.tags().size() + examples.rows().size();
            }
        }
        return count;
    }

    @Override
    public String toString() {
        return "ParsedFeature[line=" + line + ", children=" + children.size() + "]";
    }
}
