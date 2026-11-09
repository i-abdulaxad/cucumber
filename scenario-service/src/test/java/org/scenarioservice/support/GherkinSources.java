package org.scenarioservice.support;

/**
 * The Gherkin sources of the spec's glossary ("Gherkin sources used in the ACs"), exactly as printed there: two
 * spaces per indentation level, line feeds, a final newline. Line numbers matter (AC-1.2, AC-1.4).
 */
public final class GherkinSources {

    public static final String PAYMENT = """
            @checkout
            Feature: Payment
              Customers pay for their basket.
            
              Background:
                Given a basket with these items:
                  | item  | price |
                  | Mug   | 8.00  |
                  | Shirt | 20.00 |
            
              @smoke
              Scenario: Pay by card
                When the customer pays by card
                Then the receipt is:
                  \"""text
                  Total: 28.00
                  \"""
            
              Rule: Vouchers
            
                Scenario Outline: Pay with a voucher
                  When the customer pays with voucher "<code>"
                  Then the result is "<result>"
            
                  @regression
                  Examples: Valid vouchers
                    | code   | result    |
                    | SAVE10 | confirmed |
                    | SAVE20 | confirmed |
            
                  Examples: Expired vouchers
                    | code | result   |
                    | OLD5 | rejected |
            """;

    /**
     * The Payment source with "pays by credit card" and a new scenario after "Pay by card" (glossary).
     */
    public static final String PAYMENT_V2 = PAYMENT
            .replace("    When the customer pays by card\n", "    When the customer pays by credit card\n")
            .replace("""
                          \"""
                    
                      Rule: Vouchers
                    """, """
                          \"""
                    
                      Scenario: Pay by bank transfer
                        When the customer pays by bank transfer
                        Then the order waits for payment
                    
                      Rule: Vouchers
                    """);

    public static final String ADDRESS = """
            @address @smoke
            Feature: Address
              Scenario: Add an address
                When the customer adds an address
                Then the address is saved
            """;

    public static final String INVOICE = """
            @billing
            Feature: Invoice
              Scenario: Send an invoice
                Given an order
                Then an invoice is sent
            """;

    public static final String GREETING = """
            Feature: Greeting
              Scenario Outline: Greet <name>
                Given the user "<name>"
                Then the greeting is:
                  \"""
                  Hello, <name>!
                  \"""
            
                Examples:
                  | name |
                  | Ana  |
            """;

    public static final String FRENCH = "# language: fr\nFonctionnalité: Paiement\n  Scénario: Payer\n"
            + "    Soit un panier\n    Alors la commande est confirmée\n";

    public static final String STEP_BEFORE_FEATURE = "Given a step before the feature\nFeature: Late\n";

    public static final String INCONSISTENT_CELLS = "Feature: Table\n  Scenario: Rows\n    Given a table\n"
            + "      | a | b |\n      | 1 |\n";

    public static final String STEP_AFTER_EXAMPLES = "Feature: Outline\n  Scenario Outline: O\n    Given <x>\n"
            + "    Examples:\n      | x |\n      | 1 |\n    Then a late step\n";

    public static final String UNKNOWN_LANGUAGE = "# language: xx\nFeature: Unknown\n";

    public static final String NO_FEATURE = "# only a comment\n";

    private GherkinSources() {
    }
}
