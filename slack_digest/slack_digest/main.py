"""
Main CLI interface for Slack Digest Tool.
"""

import click
from rich.console import Console
from rich.table import Table
from rich import box
import sys

from .preferences import PreferenceManager
from .database import Database
from .slack_client import SlackClient
from .analyzer import ImportanceAnalyzer
from .digest import DigestGenerator
from .scheduler import run_scheduler


console = Console()


@click.group()
@click.version_option(version="1.0.0")
def cli():
    """
    Slack Digest Tool - Intelligent daily digests of your Slack messages.

    Automatically prioritizes messages based on importance and provides
    actionable recommendations.
    """
    pass


@cli.command()
@click.option('--format', type=click.Choice(['console', 'html', 'markdown', 'json']),
              help='Output format (overrides config)')
@click.option('--save', is_flag=True, help='Save digest to file')
@click.option('--hours', type=int, help='Hours to look back (overrides config)')
def generate(format, save, hours):
    """Generate a digest of recent Slack messages."""
    try:
        # Load preferences
        prefs = PreferenceManager()

        # Validate config
        errors = prefs.validate()
        if errors:
            console.print("[red]Configuration errors:[/red]")
            for error in errors:
                console.print(f"  - {error}")
            sys.exit(1)

        # Override config if specified
        if hours:
            prefs.config['digest']['lookback_hours'] = hours

        # Initialize components
        with Database(prefs.get_database_path()) as db:
            slack = SlackClient(prefs.get_slack_token(), prefs.get_user_id())
            analyzer = ImportanceAnalyzer(prefs.config, db, slack)
            digest_gen = DigestGenerator(slack, prefs.config)

            console.print("[cyan]Fetching messages from Slack...[/cyan]")

            # Fetch messages
            lookback_hours = prefs.get_lookback_hours()
            messages = slack.get_all_recent_messages(hours_back=lookback_hours)

            console.print(f"[green]Found {len(messages)} messages[/green]")

            if not messages:
                console.print("[yellow]No messages found in the specified time period.[/yellow]")
                return

            console.print("[cyan]Analyzing message importance...[/cyan]")

            # Analyze messages
            scored_messages = analyzer.analyze_messages(messages)

            # Auto-adjust priorities based on learning
            analyzer.auto_adjust_priorities()

            # Get recommendations
            recommendations = analyzer.get_action_recommendations(scored_messages)

            console.print("[cyan]Generating digest...[/cyan]")

            # Generate digest
            digest_data = digest_gen.generate_digest(scored_messages, recommendations)

            # Mark messages as processed
            for message in scored_messages:
                db.mark_message_processed(
                    message['id'],
                    message['channel_id'],
                    message['timestamp'],
                    message['importance_score'],
                    included_in_digest=message['importance_score'] >= prefs.get_min_score()
                )

            # Record digest
            if digest_data['included_messages'] > 0:
                db.record_digest(
                    digest_data['included_messages'],
                    digest_data['stats']['avg_importance']
                )

            # Output digest
            if save or format != 'console':
                digest_gen.save_digest(digest_data, format)
            else:
                digest_gen.format_console(digest_data)

            console.print("\n[green]Digest generated successfully![/green]")

    except Exception as e:
        console.print(f"[red]Error: {e}[/red]")
        if '--debug' in sys.argv:
            raise
        sys.exit(1)


@cli.group()
def config():
    """Manage configuration and preferences."""
    pass


@config.command('show')
def config_show():
    """Show current configuration."""
    try:
        prefs = PreferenceManager()
        console.print(prefs.show_config())
    except Exception as e:
        console.print(f"[red]Error: {e}[/red]")
        sys.exit(1)


@config.command('set-channel')
@click.argument('channel_name')
@click.option('--priority', type=click.Choice(['ignore', 'low', 'medium', 'high']),
              required=True, help='Channel priority')
def config_set_channel(channel_name, priority):
    """Set priority for a channel."""
    try:
        prefs = PreferenceManager()
        db = Database(prefs.get_database_path())
        slack = SlackClient(prefs.get_slack_token())

        # Remove # prefix if present
        if channel_name.startswith('#'):
            channel_name = channel_name[1:]

        # Find channel
        channels = slack.get_channels()
        channel = next((c for c in channels if c['name'] == channel_name), None)

        if not channel:
            console.print(f"[red]Channel not found: {channel_name}[/red]")
            sys.exit(1)

        db.set_channel_priority(channel['id'], channel['name'], priority)
        console.print(f"[green]Set #{channel_name} priority to: {priority}[/green]")

    except Exception as e:
        console.print(f"[red]Error: {e}[/red]")
        sys.exit(1)


@config.command('add-vip')
@click.argument('user_names', nargs=-1, required=True)
def config_add_vip(user_names):
    """Add users to VIP list."""
    try:
        prefs = PreferenceManager()
        db = Database(prefs.get_database_path())
        slack = SlackClient(prefs.get_slack_token())

        for user_name in user_names:
            # Remove @ prefix if present
            if user_name.startswith('@'):
                user_name = user_name[1:]

            # For simplicity, we'll store by name
            # In production, you'd want to resolve to user ID
            db.set_user_priority(user_name, user_name, 'vip')
            console.print(f"[green]Added {user_name} to VIP list[/green]")

    except Exception as e:
        console.print(f"[red]Error: {e}[/red]")
        sys.exit(1)


@config.command('add-keywords')
@click.argument('keywords', nargs=-1, required=True)
def config_add_keywords(keywords):
    """Add keywords to track."""
    try:
        prefs = PreferenceManager()
        db = Database(prefs.get_database_path())

        for keyword in keywords:
            db.add_keyword(keyword)
            console.print(f"[green]Added keyword: {keyword}[/green]")

    except Exception as e:
        console.print(f"[red]Error: {e}[/red]")
        sys.exit(1)


@config.command('remove-keyword')
@click.argument('keyword')
def config_remove_keyword(keyword):
    """Remove a keyword."""
    try:
        prefs = PreferenceManager()
        db = Database(prefs.get_database_path())

        db.remove_keyword(keyword)
        console.print(f"[green]Removed keyword: {keyword}[/green]")

    except Exception as e:
        console.print(f"[red]Error: {e}[/red]")
        sys.exit(1)


@config.command('list-channels')
def config_list_channels():
    """List all channels with their priorities."""
    try:
        prefs = PreferenceManager()
        db = Database(prefs.get_database_path())

        channels = db.get_all_channel_preferences()

        if not channels:
            console.print("[yellow]No channel priorities set[/yellow]")
            return

        table = Table(title="Channel Priorities", box=box.ROUNDED)
        table.add_column("Channel", style="cyan")
        table.add_column("Priority", style="green")
        table.add_column("Updated", style="dim")

        for ch in channels:
            table.add_row(f"#{ch['channel_name']}", ch['priority'], ch['updated_at'])

        console.print(table)

    except Exception as e:
        console.print(f"[red]Error: {e}[/red]")
        sys.exit(1)


@config.command('list-vips')
def config_list_vips():
    """List all VIP users."""
    try:
        prefs = PreferenceManager()
        db = Database(prefs.get_database_path())

        users = db.get_all_user_preferences()
        vips = [u for u in users if u['priority'] == 'vip']

        if not vips:
            console.print("[yellow]No VIP users set[/yellow]")
            return

        table = Table(title="VIP Users", box=box.ROUNDED)
        table.add_column("User", style="cyan")
        table.add_column("Updated", style="dim")

        for user in vips:
            table.add_row(user['user_name'], user['updated_at'])

        console.print(table)

    except Exception as e:
        console.print(f"[red]Error: {e}[/red]")
        sys.exit(1)


@config.command('list-keywords')
def config_list_keywords():
    """List all tracked keywords."""
    try:
        prefs = PreferenceManager()
        db = Database(prefs.get_database_path())

        keywords = db.get_all_keywords()

        if not keywords:
            console.print("[yellow]No keywords set[/yellow]")
            return

        console.print("[bold]Tracked Keywords:[/bold]")
        for kw in keywords:
            console.print(f"  • {kw}")

    except Exception as e:
        console.print(f"[red]Error: {e}[/red]")
        sys.exit(1)


@cli.command()
@click.option('--days', default=30, help='Number of days to show')
def stats(days):
    """Show interaction and digest statistics."""
    try:
        prefs = PreferenceManager()
        db = Database(prefs.get_database_path())

        # Digest stats
        digest_stats = db.get_digest_stats(days)

        if digest_stats:
            console.print("\n[bold cyan]Digest History[/bold cyan]")
            table = Table(box=box.ROUNDED)
            table.add_column("Date", style="cyan")
            table.add_column("Messages", style="green")
            table.add_column("Avg Score", style="yellow")

            for stat in digest_stats:
                table.add_row(
                    stat['digest_date'],
                    str(stat['message_count']),
                    f"{stat['avg_importance']:.1f}"
                )

            console.print(table)

        # Frequent contacts
        frequent_users = db.get_frequent_users(min_interactions=1, days=days)

        if frequent_users:
            console.print("\n[bold cyan]Most Frequent Contacts[/bold cyan]")
            table = Table(box=box.ROUNDED)
            table.add_column("User", style="cyan")
            table.add_column("Interactions", style="green")

            for user in frequent_users[:10]:
                table.add_row(user['user_name'], str(user['interaction_count']))

            console.print(table)

        # Active channels
        active_channels = db.get_active_channels(days)

        if active_channels:
            console.print("\n[bold cyan]Most Active Channels[/bold cyan]")
            table = Table(box=box.ROUNDED)
            table.add_column("Channel", style="cyan")
            table.add_column("Interactions", style="green")

            for ch in active_channels[:10]:
                table.add_row(ch['channel_id'], str(ch['interaction_count']))

            console.print(table)

    except Exception as e:
        console.print(f"[red]Error: {e}[/red]")
        sys.exit(1)


@cli.command()
@click.option('--time', help='Time to run digest (HH:MM format)')
def schedule(time):
    """Start the scheduler for daily digests."""
    try:
        prefs = PreferenceManager()

        if time:
            prefs.set('scheduler.time', time)
            prefs.set('scheduler.enabled', True)

        if not prefs.is_scheduler_enabled():
            console.print("[yellow]Scheduler is not enabled in config[/yellow]")
            console.print("Enable it with: --time HH:MM")
            sys.exit(1)

        console.print(f"[green]Starting scheduler...[/green]")
        console.print(f"Digest will run daily at: {prefs.get_scheduler_time()}")
        console.print("Press Ctrl+C to stop")

        run_scheduler(prefs)

    except KeyboardInterrupt:
        console.print("\n[yellow]Scheduler stopped[/yellow]")
    except Exception as e:
        console.print(f"[red]Error: {e}[/red]")
        sys.exit(1)


if __name__ == '__main__':
    cli()
