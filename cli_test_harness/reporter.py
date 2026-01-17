"""
Test reporting module for CLI test harness.

This module provides various report formats for test results.
"""

import json
from typing import List, Dict, Any
from datetime import datetime
from pathlib import Path

from .runner import TestResult


class TestReporter:
    """Generates test reports in various formats."""

    def __init__(self, output_dir: str = "test_reports"):
        """
        Initialize the reporter.

        Args:
            output_dir: Directory to save reports
        """
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)

    def generate_summary(self, results: List[TestResult]) -> Dict[str, Any]:
        """Generate test summary statistics."""
        total = len(results)
        passed = sum(1 for r in results if r.passed)
        failed = total - passed

        total_duration = sum(r.duration for r in results)

        return {
            'total': total,
            'passed': passed,
            'failed': failed,
            'pass_rate': (passed / total * 100) if total > 0 else 0,
            'total_duration': total_duration,
            'timestamp': datetime.now().isoformat()
        }

    def print_console_report(self, results: List[TestResult]):
        """Print test results to console."""
        summary = self.generate_summary(results)

        print("\n" + "=" * 70)
        print("TEST RESULTS SUMMARY")
        print("=" * 70)
        print(f"Total Tests:     {summary['total']}")
        print(f"Passed:          {summary['passed']} ✓")
        print(f"Failed:          {summary['failed']} ✗")
        print(f"Pass Rate:       {summary['pass_rate']:.1f}%")
        print(f"Total Duration:  {summary['total_duration']:.2f}s")
        print("=" * 70)

        # Print failed tests details
        failed_tests = [r for r in results if not r.passed]
        if failed_tests:
            print("\nFAILED TESTS:")
            print("-" * 70)
            for result in failed_tests:
                print(f"\n✗ {result.test_case.name}")
                if result.error:
                    print(f"  Error: {result.error}")
                if result.exit_code is not None:
                    print(f"  Exit Code: {result.exit_code} (expected {result.test_case.expected_exit_code})")

                # Print failed validations
                for turn_result in result.turn_results:
                    for val_result in turn_result.validations:
                        if not val_result.passed:
                            print(f"  ✗ {val_result.message}")

                for val_result in result.post_validations:
                    if not val_result.passed:
                        print(f"  ✗ {val_result.message}")

        # Print tests requiring visual inspection
        visual_tests = []
        for result in results:
            for turn_result in result.turn_results:
                for val in turn_result.validations:
                    if 'visual inspection' in val.message.lower():
                        visual_tests.append((result, val.message))
            for val in result.post_validations:
                if 'visual inspection' in val.message.lower():
                    visual_tests.append((result, val.message))

        if visual_tests:
            print("\n" + "=" * 70)
            print("TESTS REQUIRING VISUAL INSPECTION")
            print("=" * 70)
            for result, message in visual_tests:
                print(f"\n👁 {result.test_case.name}")
                print(f"  {message}")

    def generate_json_report(self, results: List[TestResult], filename: str = "report.json"):
        """Generate JSON report."""
        report = {
            'summary': self.generate_summary(results),
            'results': [r.to_dict() for r in results]
        }

        output_path = self.output_dir / filename
        output_path.write_text(json.dumps(report, indent=2))
        return str(output_path)

    def generate_html_report(self, results: List[TestResult], filename: str = "report.html"):
        """Generate HTML report."""
        summary = self.generate_summary(results)

        html = f"""<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>CLI Test Report</title>
    <style>
        body {{
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
            max-width: 1200px;
            margin: 0 auto;
            padding: 20px;
            background: #f5f5f5;
        }}
        .header {{
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            padding: 30px;
            border-radius: 10px;
            margin-bottom: 20px;
        }}
        .summary {{
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
            gap: 15px;
            margin-bottom: 30px;
        }}
        .stat-card {{
            background: white;
            padding: 20px;
            border-radius: 8px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        }}
        .stat-value {{
            font-size: 2em;
            font-weight: bold;
            color: #333;
        }}
        .stat-label {{
            color: #666;
            margin-top: 5px;
        }}
        .test-list {{
            background: white;
            border-radius: 8px;
            padding: 20px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        }}
        .test-item {{
            padding: 15px;
            border-left: 4px solid #ddd;
            margin-bottom: 10px;
            background: #fafafa;
        }}
        .test-item.passed {{
            border-left-color: #4caf50;
        }}
        .test-item.failed {{
            border-left-color: #f44336;
        }}
        .test-name {{
            font-weight: bold;
            font-size: 1.1em;
            margin-bottom: 5px;
        }}
        .test-description {{
            color: #666;
            margin-bottom: 10px;
        }}
        .test-details {{
            font-size: 0.9em;
            color: #888;
        }}
        .status-badge {{
            display: inline-block;
            padding: 4px 12px;
            border-radius: 12px;
            font-size: 0.85em;
            font-weight: bold;
        }}
        .status-badge.passed {{
            background: #4caf50;
            color: white;
        }}
        .status-badge.failed {{
            background: #f44336;
            color: white;
        }}
        .visual-inspection {{
            background: #fff3cd;
            border-left: 4px solid #ffc107;
            padding: 15px;
            margin: 20px 0;
        }}
        .error-message {{
            background: #ffebee;
            border-left: 4px solid #f44336;
            padding: 10px;
            margin: 10px 0;
            font-family: monospace;
            font-size: 0.9em;
        }}
    </style>
</head>
<body>
    <div class="header">
        <h1>CLI Test Report</h1>
        <p>Generated on {summary['timestamp']}</p>
    </div>

    <div class="summary">
        <div class="stat-card">
            <div class="stat-value">{summary['total']}</div>
            <div class="stat-label">Total Tests</div>
        </div>
        <div class="stat-card">
            <div class="stat-value" style="color: #4caf50;">{summary['passed']}</div>
            <div class="stat-label">Passed</div>
        </div>
        <div class="stat-card">
            <div class="stat-value" style="color: #f44336;">{summary['failed']}</div>
            <div class="stat-label">Failed</div>
        </div>
        <div class="stat-card">
            <div class="stat-value">{summary['pass_rate']:.1f}%</div>
            <div class="stat-label">Pass Rate</div>
        </div>
        <div class="stat-card">
            <div class="stat-value">{summary['total_duration']:.2f}s</div>
            <div class="stat-label">Duration</div>
        </div>
    </div>

    <div class="test-list">
        <h2>Test Results</h2>
"""

        for result in results:
            status = "passed" if result.passed else "failed"
            badge = "✓ PASSED" if result.passed else "✗ FAILED"

            html += f"""
        <div class="test-item {status}">
            <div class="test-name">
                {result.test_case.name}
                <span class="status-badge {status}">{badge}</span>
            </div>
            <div class="test-description">{result.test_case.description}</div>
            <div class="test-details">
                Duration: {result.duration:.2f}s |
                Exit Code: {result.exit_code if result.exit_code is not None else 'N/A'} |
                Tags: {', '.join(result.test_case.tags)}
            </div>
"""

            if result.error:
                html += f"""
            <div class="error-message">
                Error: {result.error}
            </div>
"""

            # Check for visual inspections
            visual_inspections = []
            for turn_result in result.turn_results:
                for val in turn_result.validations:
                    if 'visual inspection' in val.message.lower():
                        visual_inspections.append(val.message)
            for val in result.post_validations:
                if 'visual inspection' in val.message.lower():
                    visual_inspections.append(val.message)

            if visual_inspections:
                html += """
            <div class="visual-inspection">
                <strong>👁 Visual Inspection Required:</strong><br>
"""
                for vi in visual_inspections:
                    html += f"                • {vi}<br>\n"
                html += "            </div>\n"

            html += "        </div>\n"

        html += """
    </div>
</body>
</html>
"""

        output_path = self.output_dir / filename
        output_path.write_text(html)
        return str(output_path)

    def generate_markdown_report(self, results: List[TestResult], filename: str = "report.md"):
        """Generate Markdown report."""
        summary = self.generate_summary(results)

        md = f"""# CLI Test Report

**Generated:** {summary['timestamp']}

## Summary

| Metric | Value |
|--------|-------|
| Total Tests | {summary['total']} |
| Passed | {summary['passed']} ✓ |
| Failed | {summary['failed']} ✗ |
| Pass Rate | {summary['pass_rate']:.1f}% |
| Total Duration | {summary['total_duration']:.2f}s |

## Test Results

"""

        for result in results:
            status_icon = "✓" if result.passed else "✗"
            md += f"### {status_icon} {result.test_case.name}\n\n"
            md += f"**Description:** {result.test_case.description}\n\n"
            md += f"**Status:** {'PASSED' if result.passed else 'FAILED'}\n\n"
            md += f"**Duration:** {result.duration:.2f}s\n\n"
            md += f"**Exit Code:** {result.exit_code if result.exit_code is not None else 'N/A'}\n\n"
            md += f"**Tags:** {', '.join(result.test_case.tags)}\n\n"

            if result.error:
                md += f"**Error:**\n```\n{result.error}\n```\n\n"

            md += "---\n\n"

        output_path = self.output_dir / filename
        output_path.write_text(md)
        return str(output_path)
