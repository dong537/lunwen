# TOC classification agent status

Status: READY FOR PARENT TESTS

The two RED failures in logs/toc-heading-classification-red.log are addressed.

Changes:
- DocumentRegionClassifier.java: one shared hasDefiniteContentsEvidence predicate for generated TOC fields and exact TOC 1-3 style IDs/names. Definite TOC entries cannot start body, end matter, or appendix regions.
- BodyFormatRuleChecker.java: exclude definite TOC entries before body state transitions, including missing-page entries and documents with no TOC title.
- HeadingDocumentRuleModule.java: exclude definite TOC entries before named-section checks and chapter blank-line state transitions. isTocEntryParagraph uses the shared predicate.
- HeadingRuleChecker.java: standalone check returns no heading findings and headingLevel returns 0 for definite TOC evidence.
- TocBodyClassificationRegressionTest.java: 7 behavioral tests. Parent observed initial RED 4/5 failures, then 2/7 failures for the heading-specific cases.

All guards have been re-read from disk. Maven was NOT run by this agent; parent owns test execution.

The latest production edits are only HeadingDocumentRuleModule.java and HeadingRuleChecker.java. Earlier shared predicate/body edits are already present.

Remaining scope boundary: RuleDescriptions.tocIndentSuggestion still had stale firstLineChars-only parsing during review. Parent/other agent owns that file. ContentsRuleChecker also has its own TOC style recognition; this agent did not edit it.
