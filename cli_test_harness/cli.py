"""
CLI interface for the test harness.

This module provides the command-line interface for generating and running
test cases for CLI tools.
"""

import click
import json
import yaml
from pathlib import Path
from typing import Optional

from .schema import TestSuite, TestCase
from .generator import TestGenerator
from .runner import TestRunner
from .reporter import TestReporter
from .visual import VisualInspector


@click.group()
@click.version_option(version='1.0.0')
def cli():
    """
    CLI Test Harness - A comprehensive testing framework for CLI applications.

    Generate test cases automatically from --help output and documentation,
    run multi-turn interactive tests, and validate outputs with various methods.
    """
    pass


@cli.command()
@click.argument('command')
@click.option(
    '--output', '-o',
    default='test_suite.yaml',
    help='Output file for generated test suite'
)
@click.option(
    '--format', '-f',
    type=click.Choice(['yaml', 'json']),
    default='yaml',
    help='Output format'
)
@click.option(
    '--doc', '-d',
    type=click.Path(exists=True),
    help='Documentation file to extract examples from'
)
@click.option(
    '--include-interactive',
    is_flag=True,
    help='Include template for interactive tests'
)
def generate(command: str, output: str, format: str, doc: Optional[str], include_interactive: bool):
    """
    Generate test cases for a CLI command.

    This command automatically generates a test suite by analyzing:
    - --help output to discover subcommands and options
    - Documentation files to extract examples
    - Common CLI patterns and error cases

    Example:
        cli-test-harness generate myapp --output tests/myapp.yaml
    """
    click.echo(f"Generating test cases for '{command}'...")

    generator = TestGenerator(command)

    # Generate from help
    suite = generator.generate_from_help()
    click.echo(f"✓ Generated {len(suite.test_cases)} test cases from --help output")

    # Generate from documentation if provided
    if doc:
        doc_suite = generator.generate_from_documentation(doc)
        suite.test_cases.extend(doc_suite.test_cases)
        click.echo(f"✓ Added {len(doc_suite.test_cases)} test cases from documentation")

    # Add interactive test template if requested
    if include_interactive:
        interactive_test = generator.generate_interactive_test(
            name="Interactive Test Template",
            description="Template for multi-turn interactive testing",
            interactions=[
                ("First input", "expected response"),
                ("Second input", "expected response"),
            ]
        )
        interactive_test.skip = True
        interactive_test.skip_reason = "Template - customize before running"
        suite.test_cases.append(interactive_test)
        click.echo(f"✓ Added interactive test template")

    # Save test suite
    output_path = Path(output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    if format == 'yaml':
        import yaml
        output_path.write_text(yaml.dump(suite.to_dict(), default_flow_style=False, sort_keys=False))
    else:
        output_path.write_text(json.dumps(suite.to_dict(), indent=2))

    click.echo(f"\n✓ Test suite saved to: {output}")
    click.echo(f"\nTotal test cases: {len(suite.test_cases)}")
    click.echo(f"  - Basic tests: {len([t for t in suite.test_cases if 'basic' in t.tags or 'help' in t.tags])}")
    click.echo(f"  - Subcommand tests: {len([t for t in suite.test_cases if 'subcommand' in t.tags])}")
    click.echo(f"  - Error tests: {len([t for t in suite.test_cases if 'error' in t.tags])}")
    click.echo(f"  - Skipped (need customization): {len([t for t in suite.test_cases if t.skip])}")
    click.echo(f"\nNext steps:")
    click.echo(f"  1. Review and customize the test suite in {output}")
    click.echo(f"  2. Run tests with: cli-test-harness run {output}")


@cli.command()
@click.argument('test_suite', type=click.Path(exists=True))
@click.option(
    '--filter', '-f',
    help='Filter tests by name (regex)'
)
@click.option(
    '--tags', '-t',
    multiple=True,
    help='Filter tests by tags'
)
@click.option(
    '--exclude-tags',
    multiple=True,
    help='Exclude tests with these tags'
)
@click.option(
    '--format',
    type=click.Choice(['console', 'json', 'html', 'markdown', 'all']),
    default='console',
    help='Report format'
)
@click.option(
    '--report-dir',
    default='test_reports',
    help='Directory for test reports'
)
@click.option(
    '--visual',
    is_flag=True,
    help='Enable visual inspection captures'
)
@click.option(
    '--stop-on-failure',
    is_flag=True,
    help='Stop running tests after first failure'
)
def run(
    test_suite: str, filter: Optional[str], tags: tuple,
    exclude_tags: tuple, format: str, report_dir: str,
    visual: bool, stop_on_failure: bool
):
    """
    Run test cases from a test suite.

    Execute all tests defined in a test suite file, with support for:
    - Multi-turn interactive tests
    - Multiple validation types
    - Visual inspection captures
    - Detailed reporting

    Example:
        cli-test-harness run tests/myapp.yaml --tags basic --format html
    """
    click.echo(f"Loading test suite from '{test_suite}'...")

    # Load test suite
    suite_path = Path(test_suite)
    if suite_path.suffix in ['.yaml', '.yml']:
        import yaml
        suite_data = yaml.safe_load(suite_path.read_text())
    else:
        suite_data = json.loads(suite_path.read_text())

    suite = TestSuite.from_dict(suite_data)

    # Filter tests
    filtered_tests = suite.test_cases

    if filter:
        import re
        pattern = re.compile(filter)
        filtered_tests = [t for t in filtered_tests if pattern.search(t.name)]

    if tags:
        filtered_tests = [
            t for t in filtered_tests
            if any(tag in t.tags for tag in tags)
        ]

    if exclude_tags:
        filtered_tests = [
            t for t in filtered_tests
            if not any(tag in t.tags for tag in exclude_tags)
        ]

    if not filtered_tests:
        click.echo("No tests match the specified filters.", err=True)
        return

    click.echo(f"Running {len(filtered_tests)} tests...\n")

    # Create filtered suite
    filtered_suite = TestSuite(
        name=suite.name,
        description=suite.description,
        test_cases=filtered_tests,
        global_environment=suite.global_environment
    )

    # Run tests
    runner = TestRunner(capture_screenshots=visual)
    reporter = TestReporter(output_dir=report_dir)

    results = []
    for i, test_case in enumerate(filtered_suite.test_cases, 1):
        if test_case.skip:
            click.echo(f"[{i}/{len(filtered_tests)}] {test_case.name}")
            click.echo(f"    {click.style('⊘ SKIPPED', fg='yellow')} - {test_case.skip_reason}\n")
            continue

        click.echo(f"[{i}/{len(filtered_tests)}] {test_case.name}")
        result = runner.run_test_case(test_case)
        results.append(result)

        status = click.style("✓ PASSED", fg='green') if result.passed else click.style("✗ FAILED", fg='red')
        click.echo(f"    {status} ({result.duration:.2f}s)\n")

        if stop_on_failure and not result.passed:
            click.echo("Stopping due to failure (--stop-on-failure)", err=True)
            break

    # Generate reports
    click.echo("\n" + "=" * 70)

    if format == 'console' or format == 'all':
        reporter.print_console_report(results)

    if format == 'json' or format == 'all':
        json_path = reporter.generate_json_report(results)
        click.echo(f"\n✓ JSON report saved to: {json_path}")

    if format == 'html' or format == 'all':
        html_path = reporter.generate_html_report(results)
        click.echo(f"✓ HTML report saved to: {html_path}")

    if format == 'markdown' or format == 'all':
        md_path = reporter.generate_markdown_report(results)
        click.echo(f"✓ Markdown report saved to: {md_path}")

    # Exit with error code if any tests failed
    failed_count = sum(1 for r in results if not r.passed)
    if failed_count > 0:
        raise click.exceptions.Exit(1)


@cli.command()
@click.argument('test_suite', type=click.Path(exists=True))
def validate(test_suite: str):
    """
    Validate a test suite file.

    Check that a test suite file is properly formatted and contains
    valid test cases.

    Example:
        cli-test-harness validate tests/myapp.yaml
    """
    click.echo(f"Validating test suite '{test_suite}'...")

    try:
        suite_path = Path(test_suite)
        if suite_path.suffix in ['.yaml', '.yml']:
            import yaml
            suite_data = yaml.safe_load(suite_path.read_text())
        else:
            suite_data = json.loads(suite_path.read_text())

        suite = TestSuite.from_dict(suite_data)

        click.echo(f"✓ Test suite is valid")
        click.echo(f"\nSuite: {suite.name}")
        click.echo(f"Description: {suite.description}")
        click.echo(f"Test cases: {len(suite.test_cases)}")

        # Count different types
        interactive_tests = sum(1 for t in suite.test_cases if t.interactions)
        skipped_tests = sum(1 for t in suite.test_cases if t.skip)

        click.echo(f"  - Interactive tests: {interactive_tests}")
        click.echo(f"  - Skipped tests: {skipped_tests}")

        # List all tags
        all_tags = set()
        for test in suite.test_cases:
            all_tags.update(test.tags)

        if all_tags:
            click.echo(f"\nTags: {', '.join(sorted(all_tags))}")

        click.echo("\n✓ Validation successful")

    except Exception as e:
        click.echo(f"✗ Validation failed: {str(e)}", err=True)
        raise click.exceptions.Exit(1)


@cli.command()
@click.argument('name')
@click.argument('command')
@click.option('--args', '-a', multiple=True, help='Command arguments')
@click.option('--description', '-d', default='', help='Test description')
@click.option('--output', '-o', default='test_case.yaml', help='Output file')
def create(name: str, command: str, args: tuple, description: str, output: str):
    """
    Create a single test case interactively.

    This command helps you create a custom test case with interactive prompts
    for validations and expected outputs.

    Example:
        cli-test-harness create "List files" ls --args "-la" --args "/tmp"
    """
    click.echo(f"Creating test case: {name}\n")

    test_case = TestCase(
        name=name,
        description=description or f"Test for: {command} {' '.join(args)}",
        command=command,
        args=list(args)
    )

    # Interactive validation setup
    if click.confirm("Add validations?"):
        while True:
            click.echo("\nValidation types:")
            click.echo("  1. Contains text")
            click.echo("  2. Exact match")
            click.echo("  3. Regex pattern")
            click.echo("  4. Exit code")
            click.echo("  5. File exists")

            choice = click.prompt("Select validation type (or 0 to finish)", type=int)

            if choice == 0:
                break
            elif choice == 1:
                value = click.prompt("Text to check for")
                test_case.validations.append(
                    Validation(type='contains', value=value)
                )
            elif choice == 2:
                value = click.prompt("Expected exact output")
                test_case.validations.append(
                    Validation(type='exact', value=value)
                )
            elif choice == 3:
                value = click.prompt("Regex pattern")
                test_case.validations.append(
                    Validation(type='regex', value=value)
                )
            elif choice == 4:
                value = click.prompt("Expected exit code", type=int)
                test_case.expected_exit_code = value
            elif choice == 5:
                value = click.prompt("File path to check")
                test_case.validations.append(
                    Validation(type='file_exists', value=value)
                )

    # Save test case
    suite = TestSuite(
        name="Custom Test Suite",
        description="Custom test cases",
        test_cases=[test_case]
    )

    output_path = Path(output)
    import yaml
    output_path.write_text(yaml.dump(suite.to_dict(), default_flow_style=False))

    click.echo(f"\n✓ Test case saved to: {output}")
    click.echo(f"Run with: cli-test-harness run {output}")


if __name__ == '__main__':
    cli()
