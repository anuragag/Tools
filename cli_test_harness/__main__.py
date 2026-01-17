"""
CLI Test Harness - Module entry point.

Allows running the package as a module:
    python -m cli_test_harness
"""

from .cli import cli

if __name__ == '__main__':
    cli()
