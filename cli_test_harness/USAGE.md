# CLI Test Harness - Usage Guide

This guide provides step-by-step instructions for using the CLI Test Harness.

## Installation

```bash
cd cli_test_harness
pip install -e .
```

## Basic Workflow

### Step 1: Generate Test Cases

Start by generating test cases from your CLI tool:

```bash
# Generate from --help output
cli-test-harness generate slack-digest --output tests/slack_digest.yaml

# Generate from documentation
cli-test-harness generate slack-digest --doc README.md --output tests/slack_digest.yaml

# Include interactive test template
cli-test-harness generate slack-digest --include-interactive --output tests/slack_digest.yaml
```

### Step 2: Review and Customize

Open the generated test suite file and customize:

1. **Review generated tests**: Check that they make sense
2. **Add validations**: Enhance with specific output checks
3. **Add interactive tests**: For multi-turn interactions
4. **Set skip flags**: Mark tests that need configuration
5. **Add tags**: Organize tests for filtering

Example customization:

```yaml
# Before
- name: Generate Command Help
  command: slack-digest
  args:
    - generate
    - --help
  validations:
    - type: contains
      value: "Usage:"

# After (enhanced)
- name: Generate Command Help
  command: slack-digest
  args:
    - generate
    - --help
  validations:
    - type: contains
      value: "Usage:"
      description: Should show usage
    - type: contains
      value: "--hours"
      description: Should document hours option
    - type: regex
      value: "\\d+ hours?"
      description: Should show default hours value
  tags:
    - help
    - generate
    - documentation
```

### Step 3: Validate Test Suite

Check that your test suite is properly formatted:

```bash
cli-test-harness validate tests/slack_digest.yaml
```

### Step 4: Run Tests

Run all tests:

```bash
cli-test-harness run tests/slack_digest.yaml
```

Run with filters:

```bash
# Only help tests
cli-test-harness run tests/slack_digest.yaml --tags help

# Only basic and smoke tests
cli-test-harness run tests/slack_digest.yaml --tags basic --tags smoke

# Exclude slow tests
cli-test-harness run tests/slack_digest.yaml --exclude-tags slow

# Filter by name
cli-test-harness run tests/slack_digest.yaml --filter ".*config.*"
```

### Step 5: Generate Reports

Generate different report formats:

```bash
# HTML report (opens in browser)
cli-test-harness run tests/slack_digest.yaml --format html --report-dir reports/

# JSON report (for CI/CD)
cli-test-harness run tests/slack_digest.yaml --format json --report-dir reports/

# All formats
cli-test-harness run tests/slack_digest.yaml --format all --report-dir reports/
```

## Advanced Usage

### Testing Interactive CLIs

For CLIs with multi-turn interactions:

```yaml
test_cases:
  - name: Interactive Configuration
    description: Test interactive config wizard
    command: myapp
    args:
      - configure
    interactions:
      # Turn 1
      - input: "yes"
        description: Confirm configuration
        validations:
          - type: contains
            value: "Enter API key:"
        expect_prompt: true
        wait_before_input: 0.5

      # Turn 2
      - input: "sk-test-123456"
        description: Provide API key
        validations:
          - type: contains
            value: "Configuration saved"
        wait_before_input: 0.5
```

### Environment Setup

For tests that need specific files or environment:

```yaml
test_cases:
  - name: Process File
    command: myapp
    args:
      - process
      - input.txt
    environment:
      working_directory: /tmp/myapp_test
      environment_vars:
        DEBUG: "1"
        LOG_LEVEL: "verbose"
      create_files:
        /tmp/myapp_test/input.txt: |
          Test data line 1
          Test data line 2
      cleanup_files:
        - /tmp/myapp_test/input.txt
        - /tmp/myapp_test/output.txt
    validations:
      - type: file_exists
        value: /tmp/myapp_test/output.txt
```

### Visual Inspections

For output that requires human review:

```yaml
validations:
  - type: visual_inspection
    value: manual
    description: "Verify the table is properly aligned and formatted"
    required: false
```

Enable visual capture:

```bash
cli-test-harness run tests/suite.yaml --visual --format html
```

Then review the HTML report for items needing visual inspection.

## Testing Workflow Examples

### Example 1: Test-Driven Development

```bash
# 1. Generate initial tests
cli-test-harness generate myapp --output tests/myapp.yaml

# 2. Run tests (they may fail initially)
cli-test-harness run tests/myapp.yaml --format html

# 3. Fix issues in your CLI

# 4. Re-run tests
cli-test-harness run tests/myapp.yaml

# 5. Add more specific tests as needed
```

### Example 2: Regression Testing

```bash
# Before making changes
cli-test-harness run tests/myapp.yaml --format json > before.json

# Make changes to your CLI

# After making changes
cli-test-harness run tests/myapp.yaml --format json > after.json

# Compare results
diff before.json after.json
```

### Example 3: CI/CD Integration

Create a test script `.github/workflows/cli-tests.yml`:

```yaml
name: CLI Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2

      - name: Set up Python
        uses: actions/setup-python@v2
        with:
          python-version: '3.10'

      - name: Install dependencies
        run: |
          pip install -e cli_test_harness/
          pip install -r requirements.txt

      - name: Run CLI tests
        run: |
          cli-test-harness run tests/myapp.yaml --format all --report-dir reports/

      - name: Upload reports
        uses: actions/upload-artifact@v2
        if: always()
        with:
          name: test-reports
          path: reports/
```

## Common Patterns

### Pattern 1: Help Documentation Tests

Always test that help output is comprehensive:

```yaml
test_cases:
  - name: Main Help
    command: myapp
    args: [--help]
    validations:
      - type: contains
        value: "Usage:"
      - type: contains
        value: "Commands:"
      - type: contains
        value: "Options:"
    tags: [help, documentation]

  - name: Subcommand Help
    command: myapp
    args: [subcommand, --help]
    validations:
      - type: contains
        value: "Usage: myapp subcommand"
    tags: [help, documentation]
```

### Pattern 2: Error Handling Tests

Test that errors are handled gracefully:

```yaml
test_cases:
  - name: Missing Required Argument
    command: myapp
    args: [command]
    validations:
      - type: contains
        value: "Error:"
        stream: stderr
      - type: contains
        value: "required"
        stream: stderr
    expected_exit_code: 1
    tags: [error, validation]

  - name: Invalid Option
    command: myapp
    args: [--invalid-option]
    validations:
      - type: contains
        value: "Error:"
        stream: stderr
    expected_exit_code: 2
    tags: [error]
```

### Pattern 3: Output Format Tests

Test different output formats:

```yaml
test_cases:
  - name: JSON Output
    command: myapp
    args: [list, --format, json]
    validations:
      - type: json_match
        value:
          status: "success"
    tags: [output, json]

  - name: Table Output
    command: myapp
    args: [list, --format, table]
    validations:
      - type: visual_inspection
        value: manual
        description: "Verify table formatting"
        required: false
    tags: [output, table]
```

### Pattern 4: Integration Tests

Test that components work together:

```yaml
test_cases:
  - name: End-to-End Workflow
    command: myapp
    args: [workflow]
    interactions:
      - input: "init"
        validations:
          - type: contains
            value: "Initialized"
        expect_prompt: true

      - input: "process"
        validations:
          - type: contains
            value: "Processing complete"
        expect_prompt: true

      - input: "export"
        validations:
          - type: file_exists
            value: "output.json"

      - input: "exit"
    tags: [integration, e2e]
```

## Tips and Best Practices

1. **Start Simple**: Begin with help and version tests
2. **Use Tags**: Organize tests by feature, priority, and type
3. **Set Timeouts**: Prevent tests from hanging
4. **Skip Wisely**: Use skip for tests needing setup
5. **Visual Inspection**: Use for complex formatting
6. **Iterate**: Add tests as you find issues
7. **Document**: Add clear descriptions to tests
8. **Filter**: Use tags to run subsets of tests
9. **CI/CD**: Integrate into your pipeline
10. **Review Reports**: Regularly check HTML reports

## Troubleshooting

### Issue: Tests timeout

**Solution**: Increase timeout values or check if CLI is waiting for input

```yaml
timeout: 60  # Increase test timeout
interactions:
  - input: "command"
    timeout: 30  # Increase interaction timeout
```

### Issue: Interactive tests don't work

**Solution**: Ensure proper wait times and prompt expectations

```yaml
interactions:
  - input: "command"
    expect_prompt: true  # CLI will prompt again
    wait_before_input: 0.5  # Wait before sending input
```

### Issue: Validations fail unexpectedly

**Solution**: Use visual inspection to see actual output

```yaml
validations:
  - type: visual_inspection
    value: manual
    required: false
```

Then run with `--visual --format html` to review output.

### Issue: Can't find specific output

**Solution**: Check both stdout and stderr

```yaml
validations:
  - type: contains
    value: "message"
    stream: both  # Check both streams
```

## Getting Help

- Check examples in `examples/` directory
- Review test suite schema in `schema.py`
- Use `--help` on any command
- Open issues for bugs or questions
