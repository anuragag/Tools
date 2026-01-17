"""
Test case schema definitions for CLI test harness.

This module defines the structure and validation for test cases
that can be used to test CLI tools with multi-turn interactions.
"""

from typing import List, Dict, Optional, Any, Literal
from dataclasses import dataclass, field
from enum import Enum


class ValidationType(str, Enum):
    """Types of validation that can be performed on CLI output."""
    EXACT = "exact"
    CONTAINS = "contains"
    REGEX = "regex"
    NOT_CONTAINS = "not_contains"
    JSON_MATCH = "json_match"
    EXIT_CODE = "exit_code"
    FILE_EXISTS = "file_exists"
    FILE_CONTAINS = "file_contains"
    VISUAL_INSPECTION = "visual_inspection"


class OutputStream(str, Enum):
    """Output streams to capture and validate."""
    STDOUT = "stdout"
    STDERR = "stderr"
    BOTH = "both"


@dataclass
class Validation:
    """A single validation rule for CLI output."""
    type: ValidationType
    value: Any
    stream: OutputStream = OutputStream.STDOUT
    description: Optional[str] = None
    required: bool = True

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary representation."""
        return {
            'type': self.type.value,
            'value': self.value,
            'stream': self.stream.value,
            'description': self.description,
            'required': self.required
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'Validation':
        """Create from dictionary representation."""
        return cls(
            type=ValidationType(data['type']),
            value=data['value'],
            stream=OutputStream(data.get('stream', 'stdout')),
            description=data.get('description'),
            required=data.get('required', True)
        )


@dataclass
class InteractionTurn:
    """A single turn in a multi-turn CLI interaction."""
    input: str
    description: Optional[str] = None
    validations: List[Validation] = field(default_factory=list)
    timeout: int = 30  # seconds
    expect_prompt: bool = False  # Whether to expect the CLI to wait for more input
    wait_before_input: float = 0.0  # Seconds to wait before sending input

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary representation."""
        return {
            'input': self.input,
            'description': self.description,
            'validations': [v.to_dict() for v in self.validations],
            'timeout': self.timeout,
            'expect_prompt': self.expect_prompt,
            'wait_before_input': self.wait_before_input
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'InteractionTurn':
        """Create from dictionary representation."""
        return cls(
            input=data['input'],
            description=data.get('description'),
            validations=[Validation.from_dict(v) for v in data.get('validations', [])],
            timeout=data.get('timeout', 30),
            expect_prompt=data.get('expect_prompt', False),
            wait_before_input=data.get('wait_before_input', 0.0)
        )


@dataclass
class EnvironmentSetup:
    """Environment setup for a test case."""
    working_directory: Optional[str] = None
    environment_vars: Dict[str, str] = field(default_factory=dict)
    create_files: Dict[str, str] = field(default_factory=dict)  # filepath: content
    cleanup_files: List[str] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary representation."""
        return {
            'working_directory': self.working_directory,
            'environment_vars': self.environment_vars,
            'create_files': self.create_files,
            'cleanup_files': self.cleanup_files
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'EnvironmentSetup':
        """Create from dictionary representation."""
        return cls(
            working_directory=data.get('working_directory'),
            environment_vars=data.get('environment_vars', {}),
            create_files=data.get('create_files', {}),
            cleanup_files=data.get('cleanup_files', [])
        )


@dataclass
class TestCase:
    """A complete test case for CLI testing."""
    name: str
    description: str
    command: str  # Base command to test (e.g., "myapp")
    args: List[str] = field(default_factory=list)
    interactions: List[InteractionTurn] = field(default_factory=list)
    environment: EnvironmentSetup = field(default_factory=EnvironmentSetup)
    validations: List[Validation] = field(default_factory=list)  # Post-execution validations
    tags: List[str] = field(default_factory=list)
    expected_exit_code: int = 0
    timeout: int = 60  # Total test timeout
    skip: bool = False
    skip_reason: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary representation."""
        return {
            'name': self.name,
            'description': self.description,
            'command': self.command,
            'args': self.args,
            'interactions': [i.to_dict() for i in self.interactions],
            'environment': self.environment.to_dict(),
            'validations': [v.to_dict() for v in self.validations],
            'tags': self.tags,
            'expected_exit_code': self.expected_exit_code,
            'timeout': self.timeout,
            'skip': self.skip,
            'skip_reason': self.skip_reason
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'TestCase':
        """Create from dictionary representation."""
        return cls(
            name=data['name'],
            description=data['description'],
            command=data['command'],
            args=data.get('args', []),
            interactions=[InteractionTurn.from_dict(i) for i in data.get('interactions', [])],
            environment=EnvironmentSetup.from_dict(data.get('environment', {})),
            validations=[Validation.from_dict(v) for v in data.get('validations', [])],
            tags=data.get('tags', []),
            expected_exit_code=data.get('expected_exit_code', 0),
            timeout=data.get('timeout', 60),
            skip=data.get('skip', False),
            skip_reason=data.get('skip_reason')
        )


@dataclass
class TestSuite:
    """A collection of test cases."""
    name: str
    description: str
    test_cases: List[TestCase] = field(default_factory=list)
    global_environment: EnvironmentSetup = field(default_factory=EnvironmentSetup)

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary representation."""
        return {
            'name': self.name,
            'description': self.description,
            'test_cases': [tc.to_dict() for tc in self.test_cases],
            'global_environment': self.global_environment.to_dict()
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'TestSuite':
        """Create from dictionary representation."""
        return cls(
            name=data['name'],
            description=data['description'],
            test_cases=[TestCase.from_dict(tc) for tc in data.get('test_cases', [])],
            global_environment=EnvironmentSetup.from_dict(data.get('global_environment', {}))
        )
