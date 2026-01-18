#!/bin/bash
# Demo script for visual formatting tests

set -e

echo "=================================================="
echo "  CLI Test Harness - Formatting Tests Demo"
echo "=================================================="
echo ""

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if cli-test-harness is available
if ! command -v cli-test-harness &> /dev/null; then
    echo -e "${YELLOW}Installing CLI test harness...${NC}"
    pip install -e . > /dev/null 2>&1
    echo -e "${GREEN}✓ Installed${NC}"
    echo ""
fi

# Run the formatting test suite
echo -e "${BLUE}Running formatting tests...${NC}"
echo ""

# Run with visual output enabled
cli-test-harness run examples/formatting_test_suite.yaml \
    --visual \
    --format console

echo ""
echo -e "${GREEN}=================================================="
echo "  Formatting Tests Complete!"
echo -e "==================================================${NC}"
echo ""

# Check if HTML report was requested
if [ "$1" = "--html" ]; then
    echo -e "${BLUE}Generating HTML report...${NC}"

    cli-test-harness run examples/formatting_test_suite.yaml \
        --visual \
        --format html \
        --report-dir reports/formatting

    echo ""
    echo -e "${GREEN}✓ HTML report generated: reports/formatting/index.html${NC}"
    echo ""

    # Try to open in browser (macOS/Linux)
    if command -v open &> /dev/null; then
        echo "Opening report in browser..."
        open reports/formatting/index.html
    elif command -v xdg-open &> /dev/null; then
        echo "Opening report in browser..."
        xdg-open reports/formatting/index.html
    else
        echo "Open reports/formatting/index.html in your browser to view the report"
    fi
fi

# Show where visual outputs are saved
echo ""
echo -e "${BLUE}Visual outputs saved to:${NC}"
echo "  ./visual_inspections/"
echo ""
echo -e "${YELLOW}Tip:${NC} Run with --html flag to generate interactive HTML report"
echo "  ./demo_formatting.sh --html"
echo ""
echo -e "${YELLOW}Tip:${NC} View individual test outputs:"
echo "  ls -la visual_inspections/"
echo "  open visual_inspections/*/index.html"
echo ""

# Show example of running specific tests
echo -e "${BLUE}Run specific formatting tests:${NC}"
echo "  cli-test-harness run examples/formatting_test_suite.yaml --tags table"
echo "  cli-test-harness run examples/formatting_test_suite.yaml --tags colors"
echo "  cli-test-harness run examples/formatting_test_suite.yaml --tags unicode"
echo ""
