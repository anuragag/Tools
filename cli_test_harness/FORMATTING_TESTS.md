# Visual Formatting Tests Guide

This guide explains how to use the CLI test harness to verify that your CLI application's output is properly formatted and visually appealing.

## Overview

The test harness provides multiple approaches for testing output formatting:

1. **Visual Inspection** - Manual review with HTML rendering
2. **Regex Validation** - Structural pattern matching
3. **Exact Matching** - Character-perfect validation
4. **Content Validation** - Ensure key elements are present

## Quick Start

Run the formatting test suite:

```bash
# Basic run
cli-test-harness run examples/formatting_test_suite.yaml

# With visual output capture (recommended)
cli-test-harness run examples/formatting_test_suite.yaml --visual

# Generate HTML report for review
cli-test-harness run examples/formatting_test_suite.yaml --visual --format html --report-dir reports/

# Run only formatting tests
cli-test-harness run examples/formatting_test_suite.yaml --tags formatting
```

## Test Types in the Formatting Suite

### 1. Table Formatting Tests

Validates tabular output with columns and alignment:

```yaml
- type: visual_inspection
  value: manual
  stream: stdout
  description: |
    Manually verify:
    - Columns are properly aligned
    - File sizes are right-justified
    - No broken lines or wrapping issues
  required: false
```

**What to check:**
- Column headers align with data
- Consistent spacing between columns
- Numbers are right-aligned, text left-aligned
- Separator lines match column widths

### 2. Color Output Tests

Verifies ANSI color codes render correctly:

```yaml
- type: regex
  value: '\x1b\[[0-9;]+m'
  stream: stdout
  description: Verify ANSI color codes are present

- type: visual_inspection
  value: manual
  description: Verify colors enhance readability
```

**What to check:**
- Colors appear correctly (view HTML report)
- Different file types have distinct colors
- Colors don't obscure text
- Works with and without color support

### 3. Unicode/Tree Structure Tests

Tests box-drawing characters and special symbols:

```yaml
- type: contains
  value: "├──"
  required: true

- type: visual_inspection
  description: Verify box-drawing characters render correctly
```

**What to check:**
- UTF-8 characters display properly
- Tree structure is clear and unbroken
- Indentation is consistent
- No character encoding issues

### 4. Layout and Spacing Tests

Validates overall layout with headers, sections, and spacing:

```yaml
- type: visual_inspection
  description: |
    Check layout:
    - Sections separated by blank lines
    - Header is visually distinct
    - Overall readability is good
```

**What to check:**
- Proper use of whitespace
- Clear visual hierarchy
- Sections are well-separated
- No excessive blank lines

### 5. Width-Constrained Tests

Tests behavior with limited terminal width:

```yaml
environment:
  setup:
    env_vars:
      COLUMNS: "40"
```

**What to check:**
- Text wraps appropriately
- OR truncation is clean
- No broken words
- Readability maintained

## How Visual Inspection Works

### The Validation Type

```yaml
validations:
  - type: visual_inspection
    value: manual
    stream: stdout  # or stderr, or both
    description: |
      Detailed checklist of what to verify
    required: false  # Don't fail test, just flag for review
```

### What Happens When You Run Tests

1. **Test executes** - Command runs and captures output
2. **Automated checks pass** - Regex/contains validations verify structure
3. **Visual output saved** - stdout/stderr saved to HTML files
4. **Review flagged** - Tests with `visual_inspection` marked for manual review

### Visual Output Files

When run with `--visual` flag, the harness creates:

```
visual_inspections/
├── test_name_20240118_103000/
│   ├── stdout.txt          # Raw stdout
│   ├── stderr.txt          # Raw stderr
│   ├── stdout.html         # Rendered HTML with ANSI colors
│   ├── stderr.html         # Rendered HTML
│   └── index.html          # Combined view
```

### Reviewing Visual Output

Open the HTML files in a browser to see:
- ANSI colors rendered as CSS
- Exact spacing and alignment preserved
- Monospace font for accurate representation
- Dark theme matching terminal appearance

## Best Practices

### 1. Combine Validation Types

Use both automated and visual checks:

```yaml
validations:
  # Automated - ensures structure
  - type: regex
    value: '^Column1\s+Column2\s+Column3'
    required: true

  # Automated - ensures content
  - type: contains
    value: "Expected data"
    required: true

  # Manual - ensures aesthetics
  - type: visual_inspection
    value: manual
    required: false
```

### 2. Set `required: false` for Visual Checks

Visual inspections shouldn't fail the build:

```yaml
- type: visual_inspection
  required: false  # ✓ Don't block on manual review
```

### 3. Provide Detailed Checklists

Help reviewers know what to look for:

```yaml
description: |
  Check the following:
  - [ ] Headers are bold and distinct
  - [ ] Columns align properly
  - [ ] Colors enhance readability
  - [ ] No visual artifacts
```

### 4. Use Environment Variables

Control formatting for consistent tests:

```yaml
environment:
  setup:
    env_vars:
      COLUMNS: "120"           # Set terminal width
      TERM: "xterm-256color"   # Enable colors
      NO_COLOR: "1"            # Disable colors (for plain tests)
```

### 5. Tag Your Formatting Tests

Makes them easy to run separately:

```yaml
tags:
  - formatting
  - visual
  - table
```

Run with: `cli-test-harness run suite.yaml --tags formatting`

## Example Workflow

### Development Phase

```bash
# Run formatting tests during development
cli-test-harness run examples/formatting_test_suite.yaml \
  --visual \
  --format html \
  --report-dir reports/

# Review output in browser
open reports/index.html
```

### CI/CD Pipeline

```bash
# Run automated checks only (no visual inspection)
cli-test-harness run examples/formatting_test_suite.yaml \
  --exclude-tags visual \
  --format json \
  --report-dir ci-reports/

# Fail build on errors
if [ $? -ne 0 ]; then
  echo "Formatting tests failed"
  exit 1
fi
```

### Manual Review

```bash
# Run full suite with visual output
cli-test-harness run examples/formatting_test_suite.yaml \
  --visual \
  --format all \
  --report-dir review/

# Open HTML report
open review/index.html

# Review each flagged test
# Check the visual_inspections/ directory for detailed output
```

## Common Formatting Issues to Test

### Tables and Columns

- ✓ Column alignment (left, right, center)
- ✓ Header separators
- ✓ Consistent spacing
- ✓ Proper wrapping of long values
- ✓ Empty cell handling

### Colors and ANSI

- ✓ Colors render correctly
- ✓ Color codes don't leak into text
- ✓ Graceful degradation without color support
- ✓ Contrast and readability

### Unicode and Special Characters

- ✓ Box-drawing characters
- ✓ Emoji rendering
- ✓ International characters
- ✓ Special symbols (✓, ✗, →, etc.)

### Layout and Spacing

- ✓ Consistent indentation
- ✓ Proper line breaks
- ✓ Section separation
- ✓ No trailing whitespace
- ✓ Appropriate use of blank lines

### Responsive Behavior

- ✓ Narrow terminal handling
- ✓ Wide terminal utilization
- ✓ Word wrapping
- ✓ Truncation with ellipsis
- ✓ Horizontal scrolling (if applicable)

## Writing Your Own Formatting Tests

### Step 1: Identify What to Test

- What visual elements are important?
- What should remain consistent?
- What varies based on terminal capabilities?

### Step 2: Create Automated Checks

Start with regex and contains validations:

```yaml
- type: regex
  value: '^\s*Name\s+\|\s+Value\s*$'
  description: Verify table header format
```

### Step 3: Add Visual Inspection

For subjective or complex formatting:

```yaml
- type: visual_inspection
  value: manual
  description: |
    Verify overall table appearance:
    - Borders are continuous
    - Data is readable
    - Layout is aesthetically pleasing
  required: false
```

### Step 4: Test Edge Cases

- Very long values
- Empty output
- Unicode in content
- Different terminal widths

### Step 5: Generate Reports

Run with `--visual --format html` to create reviewable output.

## Troubleshooting

### Colors Not Showing

Ensure your command includes color flags:
```yaml
command: ls
args:
  - --color=always  # Force colors even in non-TTY
```

Or set environment:
```yaml
environment:
  setup:
    env_vars:
      FORCE_COLOR: "1"
```

### Unicode Characters Broken

Check encoding:
```yaml
environment:
  setup:
    env_vars:
      LANG: "en_US.UTF-8"
      LC_ALL: "en_US.UTF-8"
```

### Spacing Issues

Set consistent terminal width:
```yaml
environment:
  setup:
    env_vars:
      COLUMNS: "120"
```

## Examples in This Repository

See `examples/formatting_test_suite.yaml` for:
- ✓ Table formatting tests
- ✓ Color output tests
- ✓ Tree structure tests
- ✓ Mixed content layout tests
- ✓ Error message formatting
- ✓ Progress indicators
- ✓ JSON pretty-printing

Run them all:
```bash
cli-test-harness run examples/formatting_test_suite.yaml --visual --format html
```

## Further Reading

- [Full Documentation](README.md) - Complete test harness guide
- [Usage Guide](USAGE.md) - Basic usage examples
- [Schema Reference](schema.py) - All validation types
- [Visual Inspector](visual.py) - Visual inspection implementation
