# CLI Test Harness

A comprehensive testing framework for CLI applications that supports multi-turn interactions, automatic test generation, visual validations, and detailed reporting.

## Features

- **Automatic Test Generation**: Generate test cases from `--help` output and documentation
- **Multi-turn Interactions**: Test interactive CLI applications with complex conversation flows
- **Rich Validation Types**:
  - Exact text matching
  - Pattern matching (regex)
  - JSON validation
  - File system checks
  - Visual inspections
  - Exit code validation
- **Multiple Report Formats**: Console, JSON, HTML, and Markdown
- **Visual Inspection Support**: Capture terminal output for manual review
- **Environment Management**: Setup and teardown test environments
- **Flexible Filtering**: Run tests by name, tags, or patterns

## Installation

### From Source

```bash
cd cli_test_harness
pip install -e .
```

### Requirements

- Python 3.8+
- Click 8.1.0+
- PyYAML 6.0+

## Quick Start

### 1. Generate Test Cases

Generate test cases automatically from a CLI tool's `--help` output:

```bash
cli-test-harness generate myapp --output tests/myapp.yaml
```

With documentation:

```bash
cli-test-harness generate myapp --doc README.md --output tests/myapp.yaml
```

### 2. Run Tests

Run all tests in a suite:

```bash
cli-test-harness run tests/myapp.yaml
```

Run with filters:

```bash
# Run only tests with specific tags
cli-test-harness run tests/myapp.yaml --tags basic --tags smoke

# Exclude certain tags
cli-test-harness run tests/myapp.yaml --exclude-tags slow

# Filter by name pattern
cli-test-harness run tests/myapp.yaml --filter ".*login.*"
```

Generate HTML report:

```bash
cli-test-harness run tests/myapp.yaml --format html --report-dir reports/
```

### 3. Validate Test Suite

Check if a test suite file is valid:

```bash
cli-test-harness validate tests/myapp.yaml
```

## Test Suite Format

Test suites are defined in YAML or JSON format:

```yaml
name: My App Test Suite
description: Comprehensive tests for My App

global_environment:
  environment_vars:
    DEBUG: "1"

test_cases:
  - name: Help Flag Test
    description: Verify --help displays usage
    command: myapp
    args:
      - --help
    validations:
      - type: contains
        value: "Usage:"
        stream: stdout
        description: Should show usage information
    tags:
      - help
      - basic
    expected_exit_code: 0
    timeout: 10

  - name: Interactive Login Test
    description: Test multi-turn login flow
    command: myapp
    args:
      - login
    interactions:
      - input: "user@example.com"
        description: Enter email
        validations:
          - type: contains
            value: "Password:"
            stream: stdout
        expect_prompt: true
        wait_before_input: 0.5

      - input: "secret123"
        description: Enter password
        validations:
          - type: contains
            value: "Login successful"
            stream: stdout
        wait_before_input: 0.5

    tags:
      - auth
      - interactive
    expected_exit_code: 0
    timeout: 30
```

## Validation Types

### 1. Exact Match

```yaml
validations:
  - type: exact
    value: "Expected exact output"
    stream: stdout
```

### 2. Contains

```yaml
validations:
  - type: contains
    value: "substring to find"
    stream: stdout
```

### 3. Not Contains

```yaml
validations:
  - type: not_contains
    value: "should not appear"
    stream: stderr
```

### 4. Regex Pattern

```yaml
validations:
  - type: regex
    value: "\\d{3}-\\d{3}-\\d{4}"
    stream: stdout
    description: Phone number format
```

### 5. JSON Match

```yaml
validations:
  - type: json_match
    value:
      status: success
      code: 200
    stream: stdout
```

### 6. File Exists

```yaml
validations:
  - type: file_exists
    value: /tmp/output.txt
    description: Output file should be created
```

### 7. File Contains

```yaml
validations:
  - type: file_contains
    value:
      file: /tmp/output.txt
      content: "Expected content"
```

### 8. Visual Inspection

```yaml
validations:
  - type: visual_inspection
    value: manual
    description: Verify table formatting is correct
    required: false
```

## Multi-turn Interactive Tests

Test interactive CLI applications with multi-turn conversations:

```yaml
test_cases:
  - name: Database Setup Wizard
    description: Test interactive setup wizard
    command: myapp
    args:
      - setup
    interactions:
      # Turn 1: Database type
      - input: "postgres"
        description: Select PostgreSQL
        validations:
          - type: contains
            value: "Enter host:"
        expect_prompt: true
        wait_before_input: 0.5

      # Turn 2: Host
      - input: "localhost"
        description: Enter database host
        validations:
          - type: contains
            value: "Enter port:"
        expect_prompt: true

      # Turn 3: Port
      - input: "5432"
        description: Enter database port
        validations:
          - type: contains
            value: "Setup complete"
        wait_before_input: 0.5
```

## Environment Setup

Configure test environment before execution:

```yaml
test_cases:
  - name: File Processing Test
    command: myapp
    args:
      - process
      - input.txt
    environment:
      working_directory: /tmp/test_workspace
      environment_vars:
        DEBUG: "1"
        LOG_LEVEL: "verbose"
      create_files:
        /tmp/test_workspace/input.txt: |
          Line 1
          Line 2
          Line 3
      cleanup_files:
        - /tmp/test_workspace/input.txt
        - /tmp/test_workspace/output.txt
```

## Report Formats

### Console (Default)

```bash
cli-test-harness run tests/suite.yaml
```

Output:
```
======================================================================
TEST RESULTS SUMMARY
======================================================================
Total Tests:     10
Passed:          8 ✓
Failed:          2 ✗
Pass Rate:       80.0%
Total Duration:  5.43s
======================================================================
```

### HTML Report

```bash
cli-test-harness run tests/suite.yaml --format html --report-dir reports/
```

Generates a beautiful HTML report with:
- Summary statistics
- Test details with pass/fail status
- Visual inspection markers
- Error messages

### JSON Report

```bash
cli-test-harness run tests/suite.yaml --format json
```

Generates machine-readable JSON for CI/CD integration:

```json
{
  "summary": {
    "total": 10,
    "passed": 8,
    "failed": 2,
    "pass_rate": 80.0,
    "total_duration": 5.43
  },
  "results": [...]
}
```

### Markdown Report

```bash
cli-test-harness run tests/suite.yaml --format markdown
```

### All Formats

```bash
cli-test-harness run tests/suite.yaml --format all
```

## Advanced Features

### Test Filtering

Run specific tests using filters:

```bash
# By tag
cli-test-harness run suite.yaml --tags smoke --tags critical

# By name pattern (regex)
cli-test-harness run suite.yaml --filter ".*authentication.*"

# Exclude tags
cli-test-harness run suite.yaml --exclude-tags slow --exclude-tags experimental
```

### Stop on Failure

```bash
cli-test-harness run suite.yaml --stop-on-failure
```

### Visual Inspection Mode

Enable visual inspection captures:

```bash
cli-test-harness run suite.yaml --visual
```

This captures terminal output for tests marked with `visual_inspection` validations.

## Examples

### Example 1: Basic Test Suite

See `examples/basic_test_suite.yaml` for a simple test suite testing the `ls` command.

### Example 2: Interactive Test Suite

See `examples/interactive_test_suite.yaml` for multi-turn interactive testing examples.

### Example 3: Formatting Test Suite

See `examples/formatting_test_suite.yaml` for comprehensive visual formatting tests including:
- Table and column alignment
- ANSI color output verification
- Unicode and tree structure rendering
- Layout and spacing validation
- Progress indicators and status displays

Run the formatting tests demo:

```bash
./demo_formatting.sh           # Console output
./demo_formatting.sh --html    # Generate HTML report
```

For a complete guide on visual formatting tests, see [FORMATTING_TESTS.md](FORMATTING_TESTS.md).

## Creating Custom Test Cases

### Interactive Creation

```bash
cli-test-harness create "List files" ls --args "-la" --args "/tmp"
```

This launches an interactive wizard to configure validations.

### Manual Creation

Create a YAML file:

```yaml
name: Custom Test Suite
description: My custom tests

test_cases:
  - name: My Test
    description: Test description
    command: mycommand
    args:
      - arg1
      - arg2
    validations:
      - type: contains
        value: "success"
    tags:
      - custom
```

## Architecture

The test harness consists of several components:

- **schema.py**: Defines test case and validation data structures
- **generator.py**: Automatically generates test cases from CLI tools
- **runner.py**: Executes test cases with multi-turn support
- **reporter.py**: Generates reports in various formats
- **visual.py**: Handles visual inspection and screenshot capture
- **cli.py**: Command-line interface

## Use Cases

### 1. Regression Testing

Automatically test that CLI behavior hasn't changed:

```bash
# Generate tests once
cli-test-harness generate myapp --output tests/regression.yaml

# Run regularly
cli-test-harness run tests/regression.yaml
```

### 2. CI/CD Integration

```bash
#!/bin/bash
cli-test-harness run tests/suite.yaml --format json --report-dir reports/
EXIT_CODE=$?
cat reports/report.json
exit $EXIT_CODE
```

### 3. Documentation Validation

Ensure examples in documentation still work:

```bash
cli-test-harness generate myapp --doc README.md --output tests/docs.yaml
cli-test-harness run tests/docs.yaml
```

### 4. Interactive Application Testing

Test complex multi-turn interactions:

```yaml
- name: Setup Wizard
  command: myapp
  args: [setup]
  interactions:
    - input: "1"
      validations:
        - type: contains
          value: "Selected: Option 1"
      expect_prompt: true
    # ... more turns ...
```

### 5. Visual Regression Testing

Capture and review visual output:

```bash
cli-test-harness run tests/suite.yaml --visual --format html
```

Then review the HTML report for visual inspection items.

## Best Practices

1. **Use Tags**: Organize tests with tags for easy filtering
   ```yaml
   tags: [smoke, auth, critical]
   ```

2. **Set Timeouts**: Prevent hanging tests
   ```yaml
   timeout: 30
   ```

3. **Skip Incomplete Tests**: Mark tests that need work
   ```yaml
   skip: true
   skip_reason: "Waiting for API implementation"
   ```

4. **Use Visual Inspection Judiciously**: For complex output that's hard to validate programmatically
   ```yaml
   validations:
     - type: visual_inspection
       value: manual
       description: "Verify table alignment"
       required: false
   ```

5. **Environment Isolation**: Use environment setup to isolate tests
   ```yaml
   environment:
     working_directory: /tmp/test_123
     cleanup_files:
       - /tmp/test_123
   ```

## Troubleshooting

### Tests Timing Out

Increase timeout values:

```yaml
timeout: 60  # Test level
interactions:
  - input: "slow command"
    timeout: 30  # Interaction level
```

### Interactive Tests Not Working

- Ensure `expect_prompt: true` is set
- Add `wait_before_input` delays
- Check that the CLI actually prompts for input

### Validations Failing

- Use `visual_inspection` to see actual output
- Check `stream` setting (stdout vs stderr vs both)
- Set `required: false` for optional validations

## Contributing

Contributions are welcome! Please ensure tests pass before submitting PRs.

## License

MIT License

## Support

For issues and questions, please open an issue on the repository.
