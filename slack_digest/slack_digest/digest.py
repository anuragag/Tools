"""
Digest generation and formatting.

Creates formatted reports of important messages.
"""

from typing import List, Dict, Any
from datetime import datetime
from rich.console import Console
from rich.table import Table
from rich.panel import Panel
from rich.markdown import Markdown
from rich import box
import json


class DigestGenerator:
    """Generates formatted digests from scored messages."""

    def __init__(self, slack_client, config: Dict[str, Any]):
        """
        Initialize digest generator.

        Args:
            slack_client: SlackClient instance
            config: Configuration dict
        """
        self.slack = slack_client
        self.config = config
        self.console = Console()

    def generate_digest(self, messages: List[Dict[str, Any]],
                       recommendations: List[Dict[str, Any]] = None) -> Dict[str, Any]:
        """
        Generate a digest from scored messages.

        Args:
            messages: List of scored messages
            recommendations: List of action recommendations

        Returns:
            Digest data dict
        """
        # Filter messages by minimum score
        min_score = self.config.get('digest', {}).get('min_score', 30)
        max_messages = self.config.get('digest', {}).get('max_messages', 50)

        filtered_messages = [
            m for m in messages
            if m.get('importance_score', 0) >= min_score
        ][:max_messages]

        # Group messages by channel if configured
        group_by_channel = self.config.get('digest', {}).get('group_by_channel', True)
        if group_by_channel:
            grouped = self._group_by_channel(filtered_messages)
        else:
            grouped = {'All Messages': filtered_messages}

        # Generate statistics
        stats = self._generate_stats(messages, filtered_messages)

        return {
            'generated_at': datetime.now().isoformat(),
            'total_messages': len(messages),
            'included_messages': len(filtered_messages),
            'grouped_messages': grouped,
            'recommendations': recommendations or [],
            'stats': stats
        }

    def _group_by_channel(self, messages: List[Dict[str, Any]]) -> Dict[str, List[Dict[str, Any]]]:
        """Group messages by channel."""
        grouped = {}

        for message in messages:
            channel_id = message.get('channel_id', 'unknown')
            channel_info = self.slack.get_channel_info(channel_id)
            channel_name = channel_info.get('name', channel_id)

            if channel_name not in grouped:
                grouped[channel_name] = []

            grouped[channel_name].append(message)

        # Sort channels by average importance
        sorted_channels = sorted(
            grouped.items(),
            key=lambda x: sum(m['importance_score'] for m in x[1]) / len(x[1]),
            reverse=True
        )

        return dict(sorted_channels)

    def _generate_stats(self, all_messages: List[Dict[str, Any]],
                       included_messages: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Generate digest statistics."""
        if not included_messages:
            return {
                'avg_importance': 0,
                'max_importance': 0,
                'min_importance': 0,
                'channels_count': 0,
                'senders_count': 0
            }

        importance_scores = [m['importance_score'] for m in included_messages]
        channels = set(m['channel_id'] for m in included_messages)
        senders = set(m['user_id'] for m in included_messages if m.get('user_id'))

        return {
            'avg_importance': sum(importance_scores) / len(importance_scores),
            'max_importance': max(importance_scores),
            'min_importance': min(importance_scores),
            'channels_count': len(channels),
            'senders_count': len(senders)
        }

    def format_console(self, digest_data: Dict[str, Any]) -> str:
        """
        Format digest for console output using rich.

        Args:
            digest_data: Digest data

        Returns:
            Formatted string
        """
        # Print header
        self.console.print("\n")
        self.console.print(Panel.fit(
            f"[bold cyan]Slack Digest[/bold cyan]\n"
            f"Generated: {datetime.fromisoformat(digest_data['generated_at']).strftime('%Y-%m-%d %H:%M:%S')}\n"
            f"Messages: {digest_data['included_messages']} of {digest_data['total_messages']} "
            f"(min score: {self.config.get('digest', {}).get('min_score', 30)})",
            box=box.DOUBLE
        ))

        # Print statistics
        if self.config.get('digest', {}).get('include_stats', True):
            stats = digest_data['stats']
            stats_table = Table(show_header=False, box=box.SIMPLE)
            stats_table.add_column("Metric", style="cyan")
            stats_table.add_column("Value", style="green")

            stats_table.add_row("Average Importance", f"{stats['avg_importance']:.1f}")
            stats_table.add_row("Max Importance", f"{stats['max_importance']:.1f}")
            stats_table.add_row("Channels", str(stats['channels_count']))
            stats_table.add_row("Unique Senders", str(stats['senders_count']))

            self.console.print("\n[bold]Statistics[/bold]")
            self.console.print(stats_table)

        # Print recommendations
        if self.config.get('output', {}).get('show_recommendations', True):
            recommendations = digest_data.get('recommendations', [])
            if recommendations:
                self.console.print("\n[bold yellow]Action Recommendations[/bold yellow]")
                for i, rec in enumerate(recommendations, 1):
                    priority_color = "red" if rec['priority'] == 'high' else "yellow"
                    self.console.print(
                        f"  {i}. [{priority_color}]{rec['action']}[/{priority_color}]"
                    )

        # Print messages grouped by channel
        grouped = digest_data['grouped_messages']
        for channel_name, messages in grouped.items():
            self.console.print(f"\n[bold blue]#{channel_name}[/bold blue] ({len(messages)} messages)")

            for message in messages:
                self._print_message(message)

        return ""

    def _print_message(self, message: Dict[str, Any]):
        """Print a single message to console."""
        sender_id = message.get('user_id', 'unknown')
        sender_info = self.slack.get_user_info(sender_id)
        sender_name = sender_info.get('display_name') or sender_info.get('name', 'Unknown')

        # Format timestamp
        timestamp = datetime.fromtimestamp(message['timestamp'])
        time_str = timestamp.strftime('%H:%M')

        # Importance indicator
        score = message['importance_score']
        if score >= 80:
            importance_marker = "[red]●●●[/red]"
        elif score >= 60:
            importance_marker = "[yellow]●●○[/yellow]"
        else:
            importance_marker = "[green]●○○[/green]"

        # Truncate long messages
        text = message.get('text', '')
        if len(text) > 200:
            text = text[:200] + "..."

        # Get permalink
        permalink = self.slack.get_permalink(message['channel_id'], message['ts'])

        self.console.print(
            f"  {importance_marker} [cyan]{sender_name}[/cyan] "
            f"[dim]{time_str}[/dim] - {text}"
        )
        self.console.print(f"    [dim blue]{permalink}[/dim blue]")
        self.console.print()

    def format_markdown(self, digest_data: Dict[str, Any]) -> str:
        """
        Format digest as Markdown.

        Args:
            digest_data: Digest data

        Returns:
            Markdown string
        """
        lines = []

        # Header
        lines.append("# Slack Digest")
        lines.append("")
        lines.append(f"**Generated:** {datetime.fromisoformat(digest_data['generated_at']).strftime('%Y-%m-%d %H:%M:%S')}")
        lines.append(f"**Messages:** {digest_data['included_messages']} of {digest_data['total_messages']}")
        lines.append("")

        # Statistics
        if self.config.get('digest', {}).get('include_stats', True):
            stats = digest_data['stats']
            lines.append("## Statistics")
            lines.append("")
            lines.append(f"- **Average Importance:** {stats['avg_importance']:.1f}")
            lines.append(f"- **Max Importance:** {stats['max_importance']:.1f}")
            lines.append(f"- **Channels:** {stats['channels_count']}")
            lines.append(f"- **Unique Senders:** {stats['senders_count']}")
            lines.append("")

        # Recommendations
        if self.config.get('output', {}).get('show_recommendations', True):
            recommendations = digest_data.get('recommendations', [])
            if recommendations:
                lines.append("## Action Recommendations")
                lines.append("")
                for rec in recommendations:
                    priority = "🔴" if rec['priority'] == 'high' else "🟡"
                    lines.append(f"{priority} **{rec['action']}**")
                lines.append("")

        # Messages
        lines.append("## Messages")
        lines.append("")

        grouped = digest_data['grouped_messages']
        for channel_name, messages in grouped.items():
            lines.append(f"### #{channel_name} ({len(messages)} messages)")
            lines.append("")

            for message in messages:
                sender_id = message.get('user_id', 'unknown')
                sender_info = self.slack.get_user_info(sender_id)
                sender_name = sender_info.get('display_name') or sender_info.get('name', 'Unknown')

                timestamp = datetime.fromtimestamp(message['timestamp'])
                time_str = timestamp.strftime('%H:%M')

                score = message['importance_score']
                importance = "🔴" if score >= 80 else "🟡" if score >= 60 else "🟢"

                text = message.get('text', '')
                permalink = self.slack.get_permalink(message['channel_id'], message['ts'])

                lines.append(f"{importance} **{sender_name}** ({time_str}) - Score: {score:.0f}")
                lines.append(f"> {text}")
                lines.append(f"[View Message]({permalink})")
                lines.append("")

        return "\n".join(lines)

    def format_html(self, digest_data: Dict[str, Any]) -> str:
        """
        Format digest as HTML.

        Args:
            digest_data: Digest data

        Returns:
            HTML string
        """
        html_parts = []

        # Header
        html_parts.append("""
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Slack Digest</title>
    <style>
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
            max-width: 900px;
            margin: 0 auto;
            padding: 20px;
            background: #f5f5f5;
        }
        .header {
            background: #4A154B;
            color: white;
            padding: 20px;
            border-radius: 8px;
            margin-bottom: 20px;
        }
        .stats {
            background: white;
            padding: 15px;
            border-radius: 8px;
            margin-bottom: 20px;
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
            gap: 15px;
        }
        .stat-item {
            padding: 10px;
            border-left: 3px solid #4A154B;
        }
        .recommendations {
            background: #FFF3CD;
            border: 1px solid #FFC107;
            padding: 15px;
            border-radius: 8px;
            margin-bottom: 20px;
        }
        .channel {
            background: white;
            padding: 20px;
            border-radius: 8px;
            margin-bottom: 20px;
        }
        .channel-header {
            font-size: 1.2em;
            font-weight: bold;
            color: #4A154B;
            margin-bottom: 15px;
            border-bottom: 2px solid #e0e0e0;
            padding-bottom: 10px;
        }
        .message {
            padding: 15px;
            border-left: 3px solid #e0e0e0;
            margin-bottom: 15px;
            background: #fafafa;
        }
        .message.high { border-left-color: #dc3545; }
        .message.medium { border-left-color: #ffc107; }
        .message.low { border-left-color: #28a745; }
        .message-header {
            display: flex;
            justify-content: space-between;
            margin-bottom: 8px;
            font-weight: 500;
        }
        .sender { color: #1264a3; }
        .time { color: #666; font-size: 0.9em; }
        .score {
            background: #4A154B;
            color: white;
            padding: 2px 8px;
            border-radius: 12px;
            font-size: 0.85em;
        }
        .message-text {
            color: #1d1c1d;
            line-height: 1.5;
        }
        .permalink {
            margin-top: 8px;
        }
        .permalink a {
            color: #1264a3;
            text-decoration: none;
            font-size: 0.9em;
        }
    </style>
</head>
<body>
""")

        # Header
        gen_time = datetime.fromisoformat(digest_data['generated_at']).strftime('%Y-%m-%d %H:%M:%S')
        html_parts.append(f"""
    <div class="header">
        <h1>Slack Digest</h1>
        <p>Generated: {gen_time}</p>
        <p>Messages: {digest_data['included_messages']} of {digest_data['total_messages']}</p>
    </div>
""")

        # Statistics
        if self.config.get('digest', {}).get('include_stats', True):
            stats = digest_data['stats']
            html_parts.append("""
    <div class="stats">
""")
            html_parts.append(f"""
        <div class="stat-item">
            <div style="color: #666;">Average Importance</div>
            <div style="font-size: 1.5em; font-weight: bold;">{stats['avg_importance']:.1f}</div>
        </div>
        <div class="stat-item">
            <div style="color: #666;">Max Importance</div>
            <div style="font-size: 1.5em; font-weight: bold;">{stats['max_importance']:.1f}</div>
        </div>
        <div class="stat-item">
            <div style="color: #666;">Channels</div>
            <div style="font-size: 1.5em; font-weight: bold;">{stats['channels_count']}</div>
        </div>
        <div class="stat-item">
            <div style="color: #666;">Unique Senders</div>
            <div style="font-size: 1.5em; font-weight: bold;">{stats['senders_count']}</div>
        </div>
""")
            html_parts.append("    </div>")

        # Recommendations
        if self.config.get('output', {}).get('show_recommendations', True):
            recommendations = digest_data.get('recommendations', [])
            if recommendations:
                html_parts.append("""
    <div class="recommendations">
        <h2>Action Recommendations</h2>
        <ul>
""")
                for rec in recommendations:
                    html_parts.append(f"            <li><strong>{rec['action']}</strong></li>")
                html_parts.append("""
        </ul>
    </div>
""")

        # Messages
        grouped = digest_data['grouped_messages']
        for channel_name, messages in grouped.items():
            html_parts.append(f"""
    <div class="channel">
        <div class="channel-header">#{channel_name} ({len(messages)} messages)</div>
""")

            for message in messages:
                sender_id = message.get('user_id', 'unknown')
                sender_info = self.slack.get_user_info(sender_id)
                sender_name = sender_info.get('display_name') or sender_info.get('name', 'Unknown')

                timestamp = datetime.fromtimestamp(message['timestamp'])
                time_str = timestamp.strftime('%H:%M')

                score = message['importance_score']
                priority_class = 'high' if score >= 80 else 'medium' if score >= 60 else 'low'

                text = message.get('text', '').replace('<', '&lt;').replace('>', '&gt;')
                permalink = self.slack.get_permalink(message['channel_id'], message['ts'])

                html_parts.append(f"""
        <div class="message {priority_class}">
            <div class="message-header">
                <span class="sender">{sender_name}</span>
                <span>
                    <span class="time">{time_str}</span>
                    <span class="score">{score:.0f}</span>
                </span>
            </div>
            <div class="message-text">{text}</div>
            <div class="permalink">
                <a href="{permalink}" target="_blank">View in Slack →</a>
            </div>
        </div>
""")

            html_parts.append("    </div>")

        # Footer
        html_parts.append("""
</body>
</html>
""")

        return "\n".join(html_parts)

    def format_json(self, digest_data: Dict[str, Any]) -> str:
        """
        Format digest as JSON.

        Args:
            digest_data: Digest data

        Returns:
            JSON string
        """
        return json.dumps(digest_data, indent=2)

    def save_digest(self, digest_data: Dict[str, Any], format: str = None):
        """
        Save digest to file.

        Args:
            digest_data: Digest data
            format: Output format (overrides config)
        """
        output_format = format or self.config.get('output', {}).get('format', 'console')

        if output_format == 'html':
            output_path = self.config.get('output', {}).get('html_output', 'digest.html')
            content = self.format_html(digest_data)
            with open(output_path, 'w') as f:
                f.write(content)
            print(f"Digest saved to: {output_path}")

        elif output_format == 'markdown':
            output_path = self.config.get('output', {}).get('markdown_output', 'digest.md')
            content = self.format_markdown(digest_data)
            with open(output_path, 'w') as f:
                f.write(content)
            print(f"Digest saved to: {output_path}")

        elif output_format == 'json':
            output_path = 'digest.json'
            content = self.format_json(digest_data)
            with open(output_path, 'w') as f:
                f.write(content)
            print(f"Digest saved to: {output_path}")

        elif output_format == 'console':
            self.format_console(digest_data)
