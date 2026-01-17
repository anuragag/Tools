"""
Visual inspection and screenshot capture for CLI test harness.

This module provides functionality to capture terminal output visually
and facilitate manual inspection of test results.
"""

import subprocess
import os
from typing import Optional
from pathlib import Path
from datetime import datetime


class VisualInspector:
    """Handles visual inspection and screenshot capture."""

    def __init__(self, output_dir: str = "visual_inspections"):
        """
        Initialize the visual inspector.

        Args:
            output_dir: Directory to save screenshots and visual outputs
        """
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)

    def capture_screenshot(self, name: str) -> Optional[str]:
        """
        Capture a screenshot of the terminal.

        Args:
            name: Name for the screenshot

        Returns:
            Path to saved screenshot, or None if capture failed
        """
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"{name}_{timestamp}.png"
        output_path = self.output_dir / filename

        try:
            # Try different screenshot methods based on platform
            if os.name == 'posix':
                # Try scrot (Linux)
                result = subprocess.run(
                    ['which', 'scrot'],
                    capture_output=True,
                    timeout=5
                )
                if result.returncode == 0:
                    subprocess.run(
                        ['scrot', '-u', str(output_path)],
                        timeout=10
                    )
                    return str(output_path)

                # Try gnome-screenshot (Linux)
                result = subprocess.run(
                    ['which', 'gnome-screenshot'],
                    capture_output=True,
                    timeout=5
                )
                if result.returncode == 0:
                    subprocess.run(
                        ['gnome-screenshot', '-w', '-f', str(output_path)],
                        timeout=10
                    )
                    return str(output_path)

                # Try import from ImageMagick (Linux/macOS)
                result = subprocess.run(
                    ['which', 'import'],
                    capture_output=True,
                    timeout=5
                )
                if result.returncode == 0:
                    subprocess.run(
                        ['import', '-window', 'root', str(output_path)],
                        timeout=10
                    )
                    return str(output_path)

            elif os.name == 'nt':
                # Windows - use PowerShell
                subprocess.run(
                    [
                        'powershell',
                        '-Command',
                        f'Add-Type -AssemblyName System.Windows.Forms; '
                        f'[System.Windows.Forms.Screen]::PrimaryScreen.Bounds | '
                        f'ForEach-Object {{ '
                        f'$bmp = New-Object System.Drawing.Bitmap $_.Width, $_.Height; '
                        f'$graphics = [System.Drawing.Graphics]::FromImage($bmp); '
                        f'$graphics.CopyFromScreen($_.Location, [System.Drawing.Point]::Empty, $_.Size); '
                        f'$bmp.Save("{output_path}"); '
                        f'}}'
                    ],
                    timeout=10
                )
                return str(output_path)

        except Exception as e:
            print(f"Warning: Could not capture screenshot: {e}")

        return None

    def save_terminal_output(
        self, name: str, stdout: str, stderr: str,
        include_ansi: bool = True
    ) -> str:
        """
        Save terminal output to files for visual inspection.

        Args:
            name: Name for the output files
            stdout: Standard output content
            stderr: Standard error content
            include_ansi: Whether to preserve ANSI color codes

        Returns:
            Path to directory containing saved outputs
        """
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        dir_name = f"{name}_{timestamp}"
        output_path = self.output_dir / dir_name
        output_path.mkdir(parents=True, exist_ok=True)

        # Save stdout
        stdout_file = output_path / "stdout.txt"
        stdout_file.write_text(stdout)

        # Save stderr
        stderr_file = output_path / "stderr.txt"
        stderr_file.write_text(stderr)

        # If ANSI codes present, also save HTML version for viewing
        if include_ansi and ('\033[' in stdout or '\033[' in stderr):
            self._save_ansi_html(stdout, output_path / "stdout.html")
            self._save_ansi_html(stderr, output_path / "stderr.html")

        # Create index file
        index_file = output_path / "index.html"
        index_file.write_text(f"""<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Visual Inspection: {name}</title>
    <style>
        body {{
            font-family: monospace;
            background: #1e1e1e;
            color: #d4d4d4;
            padding: 20px;
        }}
        .header {{
            background: #252526;
            padding: 20px;
            margin-bottom: 20px;
            border-radius: 5px;
        }}
        .section {{
            background: #252526;
            padding: 20px;
            margin-bottom: 20px;
            border-radius: 5px;
        }}
        pre {{
            white-space: pre-wrap;
            word-wrap: break-word;
        }}
        .stdout {{ color: #d4d4d4; }}
        .stderr {{ color: #f48771; }}
    </style>
</head>
<body>
    <div class="header">
        <h1>Visual Inspection: {name}</h1>
        <p>Timestamp: {timestamp}</p>
    </div>

    <div class="section">
        <h2>Standard Output</h2>
        <pre class="stdout">{self._escape_html(stdout)}</pre>
    </div>

    <div class="section">
        <h2>Standard Error</h2>
        <pre class="stderr">{self._escape_html(stderr)}</pre>
    </div>
</body>
</html>
""")

        return str(output_path)

    def _save_ansi_html(self, text: str, output_path: Path):
        """Convert ANSI codes to HTML and save."""
        # Basic ANSI to HTML conversion
        html = self._ansi_to_html(text)

        output_path.write_text(f"""<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <style>
        body {{
            font-family: monospace;
            background: #1e1e1e;
            color: #d4d4d4;
            padding: 20px;
        }}
        pre {{
            white-space: pre-wrap;
            word-wrap: break-word;
        }}
        .ansi-black {{ color: #000000; }}
        .ansi-red {{ color: #cd3131; }}
        .ansi-green {{ color: #0dbc79; }}
        .ansi-yellow {{ color: #e5e510; }}
        .ansi-blue {{ color: #2472c8; }}
        .ansi-magenta {{ color: #bc3fbc; }}
        .ansi-cyan {{ color: #11a8cd; }}
        .ansi-white {{ color: #e5e5e5; }}
        .ansi-bright-black {{ color: #666666; }}
        .ansi-bright-red {{ color: #f14c4c; }}
        .ansi-bright-green {{ color: #23d18b; }}
        .ansi-bright-yellow {{ color: #f5f543; }}
        .ansi-bright-blue {{ color: #3b8eea; }}
        .ansi-bright-magenta {{ color: #d670d6; }}
        .ansi-bright-cyan {{ color: #29b8db; }}
        .ansi-bright-white {{ color: #ffffff; }}
        .ansi-bold {{ font-weight: bold; }}
    </style>
</head>
<body>
    <pre>{html}</pre>
</body>
</html>
""")

    def _ansi_to_html(self, text: str) -> str:
        """Convert ANSI escape codes to HTML."""
        import re

        # Basic implementation - can be enhanced
        # Remove ANSI codes for now (full implementation would convert to spans)
        ansi_escape = re.compile(r'\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])')
        return self._escape_html(ansi_escape.sub('', text))

    def _escape_html(self, text: str) -> str:
        """Escape HTML special characters."""
        return (
            text.replace('&', '&amp;')
            .replace('<', '&lt;')
            .replace('>', '&gt;')
            .replace('"', '&quot;')
            .replace("'", '&#39;')
        )

    def create_comparison_view(
        self, name: str, expected: str, actual: str
    ) -> str:
        """
        Create a side-by-side comparison view.

        Args:
            name: Name for the comparison
            expected: Expected output
            actual: Actual output

        Returns:
            Path to comparison HTML file
        """
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"comparison_{name}_{timestamp}.html"
        output_path = self.output_dir / filename

        html = f"""<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Comparison: {name}</title>
    <style>
        body {{
            font-family: monospace;
            background: #1e1e1e;
            color: #d4d4d4;
            padding: 20px;
        }}
        .container {{
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 20px;
        }}
        .panel {{
            background: #252526;
            padding: 20px;
            border-radius: 5px;
        }}
        .panel h2 {{
            margin-top: 0;
            border-bottom: 2px solid #404040;
            padding-bottom: 10px;
        }}
        .expected {{ border-left: 4px solid #0dbc79; }}
        .actual {{ border-left: 4px solid #f14c4c; }}
        pre {{
            white-space: pre-wrap;
            word-wrap: break-word;
        }}
    </style>
</head>
<body>
    <h1>Comparison: {name}</h1>
    <p>Timestamp: {timestamp}</p>

    <div class="container">
        <div class="panel expected">
            <h2>Expected Output</h2>
            <pre>{self._escape_html(expected)}</pre>
        </div>

        <div class="panel actual">
            <h2>Actual Output</h2>
            <pre>{self._escape_html(actual)}</pre>
        </div>
    </div>
</body>
</html>
"""

        output_path.write_text(html)
        return str(output_path)
