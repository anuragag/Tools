"""
CLI Test Harness - A comprehensive testing framework for CLI applications.

This package provides tools for generating, running, and validating
test cases for command-line interface applications.
"""

from .schema import (
    TestCase, TestSuite, InteractionTurn, Validation,
    ValidationType, OutputStream, EnvironmentSetup
)
from .generator import TestGenerator
from .runner import TestRunner, TestResult
from .reporter import TestReporter
from .visual import VisualInspector

__version__ = '1.0.0'
__author__ = 'CLI Test Harness Team'

__all__ = [
    'TestCase',
    'TestSuite',
    'InteractionTurn',
    'Validation',
    'ValidationType',
    'OutputStream',
    'EnvironmentSetup',
    'TestGenerator',
    'TestRunner',
    'TestResult',
    'TestReporter',
    'VisualInspector',
]
