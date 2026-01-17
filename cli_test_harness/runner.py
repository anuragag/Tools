"""
Test runner for executing CLI test cases.

This module provides the core functionality for running test cases,
handling multi-turn interactions, and capturing outputs.
"""

import subprocess
import re
import json
import os
import time
import signal
from pathlib import Path
from typing import Dict, List, Optional, Tuple, Any
from dataclasses import dataclass, field
from datetime import datetime

from .schema import (
    TestCase, TestSuite, InteractionTurn, Validation,
    ValidationType, OutputStream, EnvironmentSetup
)


@dataclass
class ValidationResult:
    """Result of a single validation."""
    validation: Validation
    passed: bool
    actual_value: str
    message: str
    timestamp: datetime = field(default_factory=datetime.now)


@dataclass
class TurnResult:
    """Result of a single interaction turn."""
    turn: InteractionTurn
    stdout: str
    stderr: str
    duration: float
    validations: List[ValidationResult] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        """Check if all required validations passed."""
        return all(
            v.passed or not v.validation.required
            for v in self.validations
        )


@dataclass
class TestResult:
    """Result of a complete test case execution."""
    test_case: TestCase
    passed: bool
    exit_code: Optional[int]
    duration: float
    turn_results: List[TurnResult] = field(default_factory=list)
    post_validations: List[ValidationResult] = field(default_factory=list)
    error: Optional[str] = None
    stdout: str = ""
    stderr: str = ""
    screenshot_path: Optional[str] = None
    timestamp: datetime = field(default_factory=datetime.now)

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary representation."""
        return {
            'name': self.test_case.name,
            'passed': self.passed,
            'exit_code': self.exit_code,
            'duration': self.duration,
            'error': self.error,
            'timestamp': self.timestamp.isoformat(),
            'turn_count': len(self.turn_results),
            'turns_passed': sum(1 for t in self.turn_results if t.passed),
            'validations_total': len(self.post_validations) + sum(len(t.validations) for t in self.turn_results),
            'validations_passed': sum(1 for v in self.post_validations if v.passed) +
                                 sum(sum(1 for v in t.validations if v.passed) for t in self.turn_results)
        }


class TestRunner:
    """Executes CLI test cases and validates results."""

    def __init__(self, working_dir: Optional[str] = None, capture_screenshots: bool = False):
        """
        Initialize the test runner.

        Args:
            working_dir: Base working directory for tests
            capture_screenshots: Whether to capture terminal screenshots
        """
        self.working_dir = Path(working_dir) if working_dir else Path.cwd()
        self.capture_screenshots = capture_screenshots

    def run_test_suite(self, suite: TestSuite) -> List[TestResult]:
        """
        Run all test cases in a suite.

        Args:
            suite: The test suite to run

        Returns:
            List of test results
        """
        results = []

        # Setup global environment
        self._setup_environment(suite.global_environment)

        for test_case in suite.test_cases:
            if test_case.skip:
                print(f"⊘ Skipping: {test_case.name} - {test_case.skip_reason}")
                continue

            print(f"▶ Running: {test_case.name}")
            result = self.run_test_case(test_case)
            results.append(result)

            status = "✓" if result.passed else "✗"
            print(f"{status} {test_case.name} ({result.duration:.2f}s)")

        # Cleanup global environment
        self._cleanup_environment(suite.global_environment)

        return results

    def run_test_case(self, test_case: TestCase) -> TestResult:
        """
        Run a single test case.

        Args:
            test_case: The test case to run

        Returns:
            Test result
        """
        start_time = time.time()

        try:
            # Setup environment
            self._setup_environment(test_case.environment)

            # Determine working directory
            work_dir = test_case.environment.working_directory or str(self.working_dir)

            # Build command
            cmd = [test_case.command] + test_case.args

            # Prepare environment variables
            env = os.environ.copy()
            env.update(test_case.environment.environment_vars)

            if test_case.interactions:
                # Multi-turn interaction mode
                result = self._run_interactive(test_case, cmd, env, work_dir)
            else:
                # Single execution mode
                result = self._run_single(test_case, cmd, env, work_dir)

            # Cleanup environment
            self._cleanup_environment(test_case.environment)

            result.duration = time.time() - start_time
            return result

        except Exception as e:
            duration = time.time() - start_time
            return TestResult(
                test_case=test_case,
                passed=False,
                exit_code=None,
                duration=duration,
                error=str(e)
            )

    def _run_single(
        self, test_case: TestCase, cmd: List[str],
        env: Dict[str, str], work_dir: str
    ) -> TestResult:
        """Run a single non-interactive command."""
        try:
            process = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=test_case.timeout,
                env=env,
                cwd=work_dir
            )

            stdout = process.stdout
            stderr = process.stderr
            exit_code = process.returncode

            # Validate exit code
            passed = exit_code == test_case.expected_exit_code

            # Run validations
            post_validations = self._run_validations(
                test_case.validations, stdout, stderr, work_dir
            )

            # Update passed status based on validations
            passed = passed and all(
                v.passed or not v.validation.required
                for v in post_validations
            )

            return TestResult(
                test_case=test_case,
                passed=passed,
                exit_code=exit_code,
                duration=0,  # Will be set by caller
                stdout=stdout,
                stderr=stderr,
                post_validations=post_validations
            )

        except subprocess.TimeoutExpired:
            return TestResult(
                test_case=test_case,
                passed=False,
                exit_code=None,
                duration=0,
                error=f"Test timed out after {test_case.timeout} seconds"
            )

    def _run_interactive(
        self, test_case: TestCase, cmd: List[str],
        env: Dict[str, str], work_dir: str
    ) -> TestResult:
        """Run an interactive command with multi-turn interactions."""
        try:
            # Start process
            process = subprocess.Popen(
                cmd,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                env=env,
                cwd=work_dir,
                bufsize=0
            )

            turn_results = []
            all_stdout = []
            all_stderr = []

            # Process each interaction turn
            for turn in test_case.interactions:
                turn_start = time.time()

                # Wait if specified
                if turn.wait_before_input > 0:
                    time.sleep(turn.wait_before_input)

                # Send input
                if process.stdin:
                    process.stdin.write(turn.input + '\n')
                    process.stdin.flush()

                # Capture output (with timeout)
                stdout_chunk = ""
                stderr_chunk = ""

                try:
                    # Read available output
                    if turn.expect_prompt:
                        # For prompts, read until we get output
                        time.sleep(0.5)  # Give time for output
                        if process.stdout:
                            stdout_chunk = self._read_available(process.stdout)
                        if process.stderr:
                            stderr_chunk = self._read_available(process.stderr)
                    else:
                        # For final commands, wait for completion
                        pass

                except Exception as e:
                    stderr_chunk += f"\nError reading output: {str(e)}"

                all_stdout.append(stdout_chunk)
                all_stderr.append(stderr_chunk)

                # Run turn validations
                validations = self._run_validations(
                    turn.validations, stdout_chunk, stderr_chunk, work_dir
                )

                turn_result = TurnResult(
                    turn=turn,
                    stdout=stdout_chunk,
                    stderr=stderr_chunk,
                    duration=time.time() - turn_start,
                    validations=validations
                )
                turn_results.append(turn_result)

            # Wait for process to complete
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()

            exit_code = process.returncode

            # Combine all output
            full_stdout = '\n'.join(all_stdout)
            full_stderr = '\n'.join(all_stderr)

            # Run post validations
            post_validations = self._run_validations(
                test_case.validations, full_stdout, full_stderr, work_dir
            )

            # Determine if test passed
            passed = (
                exit_code == test_case.expected_exit_code and
                all(t.passed for t in turn_results) and
                all(v.passed or not v.validation.required for v in post_validations)
            )

            return TestResult(
                test_case=test_case,
                passed=passed,
                exit_code=exit_code,
                duration=0,  # Will be set by caller
                turn_results=turn_results,
                post_validations=post_validations,
                stdout=full_stdout,
                stderr=full_stderr
            )

        except Exception as e:
            return TestResult(
                test_case=test_case,
                passed=False,
                exit_code=None,
                duration=0,
                error=str(e)
            )

    def _read_available(self, stream) -> str:
        """Read available data from stream without blocking."""
        import select
        output = []
        while True:
            ready, _, _ = select.select([stream], [], [], 0.1)
            if ready:
                line = stream.readline()
                if not line:
                    break
                output.append(line)
            else:
                break
        return ''.join(output)

    def _run_validations(
        self, validations: List[Validation],
        stdout: str, stderr: str, work_dir: str
    ) -> List[ValidationResult]:
        """Run all validations and return results."""
        results = []

        for validation in validations:
            result = self._run_validation(validation, stdout, stderr, work_dir)
            results.append(result)

        return results

    def _run_validation(
        self, validation: Validation,
        stdout: str, stderr: str, work_dir: str
    ) -> ValidationResult:
        """Run a single validation."""
        # Get the appropriate output stream
        if validation.stream == OutputStream.STDOUT:
            output = stdout
        elif validation.stream == OutputStream.STDERR:
            output = stderr
        else:  # BOTH
            output = stdout + stderr

        # Run validation based on type
        if validation.type == ValidationType.EXACT:
            passed = output.strip() == str(validation.value).strip()
            message = "Output matches exactly" if passed else f"Expected exact match: {validation.value}"

        elif validation.type == ValidationType.CONTAINS:
            passed = str(validation.value) in output
            message = f"Contains '{validation.value}'" if passed else f"Missing: {validation.value}"

        elif validation.type == ValidationType.NOT_CONTAINS:
            passed = str(validation.value) not in output
            message = f"Does not contain '{validation.value}'" if passed else f"Should not contain: {validation.value}"

        elif validation.type == ValidationType.REGEX:
            match = re.search(str(validation.value), output)
            passed = match is not None
            message = f"Regex matched: {validation.value}" if passed else f"Regex not found: {validation.value}"

        elif validation.type == ValidationType.JSON_MATCH:
            try:
                actual_json = json.loads(output)
                expected_json = validation.value
                passed = actual_json == expected_json
                message = "JSON matches" if passed else f"JSON mismatch"
            except json.JSONDecodeError as e:
                passed = False
                message = f"Invalid JSON: {str(e)}"

        elif validation.type == ValidationType.FILE_EXISTS:
            file_path = Path(work_dir) / validation.value
            passed = file_path.exists()
            message = f"File exists: {validation.value}" if passed else f"File not found: {validation.value}"

        elif validation.type == ValidationType.FILE_CONTAINS:
            file_path = Path(work_dir) / validation.value['file']
            expected_content = validation.value['content']
            try:
                actual_content = file_path.read_text()
                passed = expected_content in actual_content
                message = f"File contains expected content" if passed else f"File missing content"
            except Exception as e:
                passed = False
                message = f"Error reading file: {str(e)}"

        elif validation.type == ValidationType.VISUAL_INSPECTION:
            # For visual inspection, we mark as passed but require manual review
            passed = True
            message = f"Visual inspection required: {validation.description}"

        else:
            passed = False
            message = f"Unknown validation type: {validation.type}"

        return ValidationResult(
            validation=validation,
            passed=passed,
            actual_value=output[:200],  # Truncate for readability
            message=message
        )

    def _setup_environment(self, env_setup: EnvironmentSetup):
        """Setup test environment."""
        # Create files
        for file_path, content in env_setup.create_files.items():
            path = Path(file_path)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)

    def _cleanup_environment(self, env_setup: EnvironmentSetup):
        """Cleanup test environment."""
        # Remove created files
        for file_path in env_setup.cleanup_files:
            path = Path(file_path)
            if path.exists():
                path.unlink()
