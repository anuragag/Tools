"""
Test case generator for CLI test harness.

This module automatically generates test cases from CLI help output,
documentation, and other sources.
"""

import subprocess
import re
from typing import List, Dict, Optional, Set, Tuple
from pathlib import Path

from .schema import (
    TestCase, TestSuite, Validation, ValidationType,
    OutputStream, EnvironmentSetup, InteractionTurn
)


class TestGenerator:
    """Generates test cases for CLI tools."""

    def __init__(self, command: str):
        """
        Initialize the test generator.

        Args:
            command: The CLI command to generate tests for
        """
        self.command = command
        self.help_output = ""
        self.subcommands: List[str] = []
        self.options: Dict[str, Dict] = {}

    def generate_from_help(self) -> TestSuite:
        """
        Generate test suite from --help output.

        Returns:
            Generated test suite
        """
        # Parse help output
        self._parse_help()

        test_cases = []

        # Generate basic help test
        test_cases.append(self._generate_help_test())

        # Generate version test if available
        if self._has_version_flag():
            test_cases.append(self._generate_version_test())

        # Generate tests for each subcommand
        for subcommand in self.subcommands:
            test_cases.extend(self._generate_subcommand_tests(subcommand))

        # Generate option combination tests
        test_cases.extend(self._generate_option_tests())

        # Generate error case tests
        test_cases.extend(self._generate_error_tests())

        return TestSuite(
            name=f"{self.command} Test Suite",
            description=f"Auto-generated test suite for {self.command}",
            test_cases=test_cases
        )

    def generate_from_documentation(self, doc_path: str) -> TestSuite:
        """
        Generate test suite from documentation.

        Args:
            doc_path: Path to documentation file (markdown, rst, etc.)

        Returns:
            Generated test suite
        """
        doc_content = Path(doc_path).read_text()
        test_cases = []

        # Extract code examples from documentation
        examples = self._extract_code_examples(doc_content)

        for idx, example in enumerate(examples):
            test_cases.append(
                TestCase(
                    name=f"Documentation Example {idx + 1}",
                    description=f"Test case from documentation: {example.get('description', '')}",
                    command=self.command,
                    args=example['args'],
                    validations=[
                        Validation(
                            type=ValidationType.EXIT_CODE,
                            value=0,
                            description="Command should succeed"
                        )
                    ],
                    tags=["documentation", "example"]
                )
            )

        return TestSuite(
            name=f"{self.command} Documentation Tests",
            description=f"Test suite from {doc_path}",
            test_cases=test_cases
        )

    def _parse_help(self):
        """Parse --help output to extract commands and options."""
        try:
            result = subprocess.run(
                [self.command, "--help"],
                capture_output=True,
                text=True,
                timeout=10
            )
            self.help_output = result.stdout + result.stderr

            # Try to parse subcommands (common patterns)
            self.subcommands = self._extract_subcommands(self.help_output)

            # Parse options
            self.options = self._extract_options(self.help_output)

        except Exception as e:
            print(f"Warning: Could not parse help output: {e}")

    def _extract_subcommands(self, help_text: str) -> List[str]:
        """Extract subcommands from help text."""
        subcommands = []

        # Pattern 1: "Commands:" section (Click-style)
        commands_section = re.search(
            r'Commands?:\s*\n((?:  \w+.*\n)+)',
            help_text,
            re.MULTILINE
        )
        if commands_section:
            for line in commands_section.group(1).split('\n'):
                match = re.match(r'\s+(\w+)', line)
                if match:
                    subcommands.append(match.group(1))

        # Pattern 2: "Available commands:" section
        if not subcommands:
            available_section = re.search(
                r'Available commands?:\s*\n((?:  \w+.*\n)+)',
                help_text,
                re.MULTILINE | re.IGNORECASE
            )
            if available_section:
                for line in available_section.group(1).split('\n'):
                    match = re.match(r'\s+(\w+)', line)
                    if match:
                        subcommands.append(match.group(1))

        # Pattern 3: usage line with {command1,command2}
        usage_match = re.search(r'\{([^}]+)\}', help_text)
        if usage_match:
            subcommands = [cmd.strip() for cmd in usage_match.group(1).split(',')]

        return subcommands

    def _extract_options(self, help_text: str) -> Dict[str, Dict]:
        """Extract options from help text."""
        options = {}

        # Pattern: -o, --option VALUE    Description
        option_pattern = re.compile(
            r'^\s*(-\w)?(?:,?\s*)(--[\w-]+)(?:\s+(\w+))?\s+(.+)$',
            re.MULTILINE
        )

        for match in option_pattern.finditer(help_text):
            short_flag = match.group(1)
            long_flag = match.group(2)
            value_name = match.group(3)
            description = match.group(4)

            options[long_flag] = {
                'short': short_flag,
                'value_name': value_name,
                'description': description,
                'required': 'required' in description.lower()
            }

        return options

    def _has_version_flag(self) -> bool:
        """Check if command has --version flag."""
        return '--version' in self.help_output or '-v' in self.help_output

    def _generate_help_test(self) -> TestCase:
        """Generate test for --help flag."""
        return TestCase(
            name="Help Flag Test",
            description="Verify --help flag displays help information",
            command=self.command,
            args=["--help"],
            validations=[
                Validation(
                    type=ValidationType.EXIT_CODE,
                    value=0,
                    description="Help should exit with code 0"
                ),
                Validation(
                    type=ValidationType.CONTAINS,
                    value="Usage:",
                    stream=OutputStream.BOTH,
                    description="Should contain usage information"
                )
            ],
            tags=["help", "documentation"]
        )

    def _generate_version_test(self) -> TestCase:
        """Generate test for --version flag."""
        return TestCase(
            name="Version Flag Test",
            description="Verify --version flag displays version",
            command=self.command,
            args=["--version"],
            validations=[
                Validation(
                    type=ValidationType.EXIT_CODE,
                    value=0,
                    description="Version should exit with code 0"
                ),
                Validation(
                    type=ValidationType.REGEX,
                    value=r'\d+\.\d+\.\d+',
                    stream=OutputStream.BOTH,
                    description="Should contain version number"
                )
            ],
            tags=["version", "metadata"]
        )

    def _generate_subcommand_tests(self, subcommand: str) -> List[TestCase]:
        """Generate tests for a subcommand."""
        test_cases = []

        # Test subcommand help
        test_cases.append(
            TestCase(
                name=f"Subcommand Help: {subcommand}",
                description=f"Verify {subcommand} --help works",
                command=self.command,
                args=[subcommand, "--help"],
                validations=[
                    Validation(
                        type=ValidationType.CONTAINS,
                        value="Usage:",
                        stream=OutputStream.BOTH,
                        description="Should display help"
                    )
                ],
                tags=["subcommand", "help", subcommand]
            )
        )

        # Test basic subcommand execution (may fail without required args)
        test_cases.append(
            TestCase(
                name=f"Subcommand Basic: {subcommand}",
                description=f"Test basic execution of {subcommand}",
                command=self.command,
                args=[subcommand],
                validations=[
                    Validation(
                        type=ValidationType.VISUAL_INSPECTION,
                        value="manual",
                        description="Manual verification required for output",
                        required=False
                    )
                ],
                tags=["subcommand", "basic", subcommand],
                expected_exit_code=0,  # May need adjustment
                skip=True,
                skip_reason="May require additional arguments or configuration"
            )
        )

        return test_cases

    def _generate_option_tests(self) -> List[TestCase]:
        """Generate tests for various option combinations."""
        test_cases = []

        # Test common options individually
        common_options = [
            ('--verbose', 'Verbose output'),
            ('--debug', 'Debug mode'),
            ('--quiet', 'Quiet mode'),
            ('--json', 'JSON output'),
            ('--dry-run', 'Dry run mode'),
        ]

        for option, description in common_options:
            if option in self.help_output:
                test_cases.append(
                    TestCase(
                        name=f"Option Test: {option}",
                        description=f"Test {description}",
                        command=self.command,
                        args=[option],
                        validations=[
                            Validation(
                                type=ValidationType.VISUAL_INSPECTION,
                                value="manual",
                                description=f"Verify {description} behavior",
                                required=False
                            )
                        ],
                        tags=["option", option.strip('-')],
                        skip=True,
                        skip_reason="May require subcommand or additional context"
                    )
                )

        return test_cases

    def _generate_error_tests(self) -> List[TestCase]:
        """Generate tests for error conditions."""
        test_cases = []

        # Test invalid command
        test_cases.append(
            TestCase(
                name="Invalid Command Test",
                description="Verify error on invalid command",
                command=self.command,
                args=["invalid-command-xyz"],
                validations=[
                    Validation(
                        type=ValidationType.NOT_CONTAINS,
                        value="",
                        stream=OutputStream.STDERR,
                        description="Should output error message",
                        required=False
                    )
                ],
                expected_exit_code=1,  # May vary
                tags=["error", "negative"]
            )
        )

        # Test missing required arguments
        if self.subcommands:
            test_cases.append(
                TestCase(
                    name="Missing Subcommand Test",
                    description="Verify error when no subcommand provided",
                    command=self.command,
                    args=[],
                    validations=[
                        Validation(
                            type=ValidationType.VISUAL_INSPECTION,
                            value="manual",
                            description="Should show usage or error",
                            required=False
                        )
                    ],
                    tags=["error", "usage"]
                )
            )

        return test_cases

    def _extract_code_examples(self, doc_content: str) -> List[Dict]:
        """Extract code examples from documentation."""
        examples = []

        # Extract code blocks (markdown style)
        code_blocks = re.finditer(
            r'```(?:bash|shell|sh)?\s*\n(.+?)\n```',
            doc_content,
            re.MULTILINE | re.DOTALL
        )

        for block in code_blocks:
            code = block.group(1)
            # Look for lines starting with our command
            for line in code.split('\n'):
                line = line.strip()
                if line.startswith(self.command):
                    # Remove command and split into args
                    args_str = line[len(self.command):].strip()
                    args = args_str.split() if args_str else []

                    # Try to find description (look at preceding lines)
                    start = block.start()
                    preceding = doc_content[max(0, start - 200):start]
                    description_match = re.search(r'([^\n]+)\n*$', preceding)
                    description = description_match.group(1) if description_match else ""

                    examples.append({
                        'args': args,
                        'description': description.strip()
                    })

        return examples

    def generate_interactive_test(
        self, name: str, description: str,
        interactions: List[Tuple[str, str]]
    ) -> TestCase:
        """
        Generate a multi-turn interactive test case.

        Args:
            name: Test name
            description: Test description
            interactions: List of (input, expected_output) tuples

        Returns:
            Interactive test case
        """
        turns = []
        for input_text, expected_output in interactions:
            turns.append(
                InteractionTurn(
                    input=input_text,
                    validations=[
                        Validation(
                            type=ValidationType.CONTAINS,
                            value=expected_output,
                            description=f"Should contain: {expected_output}"
                        )
                    ] if expected_output else [],
                    expect_prompt=True
                )
            )

        return TestCase(
            name=name,
            description=description,
            command=self.command,
            interactions=turns,
            tags=["interactive", "multi-turn"]
        )
