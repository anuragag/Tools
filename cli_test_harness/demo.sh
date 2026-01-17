#!/bin/bash
# Demo script for CLI Test Harness

set -e

echo "==================================================================="
echo "CLI Test Harness Demo"
echo "==================================================================="
echo ""

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Step 1: Show help
echo -e "${BLUE}Step 1: Viewing CLI Test Harness Help${NC}"
echo "$ cli-test-harness --help"
echo ""
cli-test-harness --help || python -m cli_test_harness --help || echo "Run: pip install -e . first"
echo ""
echo "Press Enter to continue..."
read

# Step 2: Generate test cases
echo -e "${BLUE}Step 2: Generating Test Cases${NC}"
echo "$ cli-test-harness generate ls --output /tmp/demo_ls_tests.yaml"
echo ""
cli-test-harness generate ls --output /tmp/demo_ls_tests.yaml || python -m cli_test_harness generate ls --output /tmp/demo_ls_tests.yaml
echo ""
echo "Generated test suite at: /tmp/demo_ls_tests.yaml"
echo ""
echo "Press Enter to view the test suite..."
read
cat /tmp/demo_ls_tests.yaml
echo ""
echo "Press Enter to continue..."
read

# Step 3: Validate test suite
echo -e "${BLUE}Step 3: Validating Test Suite${NC}"
echo "$ cli-test-harness validate /tmp/demo_ls_tests.yaml"
echo ""
cli-test-harness validate /tmp/demo_ls_tests.yaml || python -m cli_test_harness validate /tmp/demo_ls_tests.yaml
echo ""
echo "Press Enter to continue..."
read

# Step 4: Run example test
echo -e "${BLUE}Step 4: Running Example Test Suite${NC}"
echo "$ cli-test-harness run examples/basic_test_suite.yaml"
echo ""
cli-test-harness run examples/basic_test_suite.yaml || python -m cli_test_harness run examples/basic_test_suite.yaml
echo ""
echo "Press Enter to continue..."
read

# Step 5: Generate HTML report
echo -e "${BLUE}Step 5: Generating HTML Report${NC}"
echo "$ cli-test-harness run examples/basic_test_suite.yaml --format html --report-dir /tmp/demo_reports"
echo ""
cli-test-harness run examples/basic_test_suite.yaml --format html --report-dir /tmp/demo_reports || \
  python -m cli_test_harness run examples/basic_test_suite.yaml --format html --report-dir /tmp/demo_reports
echo ""
echo -e "${GREEN}HTML report generated at: /tmp/demo_reports/report.html${NC}"
echo ""

# Step 6: Show available examples
echo -e "${BLUE}Step 6: Available Examples${NC}"
echo ""
echo "Example test suites in examples/ directory:"
ls -1 examples/*.yaml
echo ""

echo "==================================================================="
echo -e "${GREEN}Demo Complete!${NC}"
echo "==================================================================="
echo ""
echo "Next steps:"
echo "1. Open /tmp/demo_reports/report.html in your browser"
echo "2. Review examples/ directory for more test suites"
echo "3. Create your own test suite with: cli-test-harness create"
echo "4. Read USAGE.md for comprehensive guide"
echo ""
