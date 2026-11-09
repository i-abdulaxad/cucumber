package org.scenarioservice.gherkin;

import java.util.List;

/**
 * One data row of an Examples table: its line and cells. {@link #toString()} shows no content (P16).
 */
public record ParsedRow(int line, List<String> cells) {

    public ParsedRow {
        cells = List.copyOf(cells);
    }

    @Override
    public String toString() {
        return "ParsedRow[line=" + line + "]";
    }
}
