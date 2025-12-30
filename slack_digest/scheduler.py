"""
Scheduler for automated daily digests.
"""

import schedule
import time
from datetime import datetime

from .preferences import PreferenceManager
from .database import Database
from .slack_client import SlackClient
from .analyzer import ImportanceAnalyzer
from .digest import DigestGenerator


def generate_scheduled_digest():
    """Generate a digest on schedule."""
    print(f"\n[{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}] Generating scheduled digest...")

    try:
        # Load preferences
        prefs = PreferenceManager()

        # Initialize components
        with Database(prefs.get_database_path()) as db:
            slack = SlackClient(prefs.get_slack_token(), prefs.get_user_id())
            analyzer = ImportanceAnalyzer(prefs.config, db, slack)
            digest_gen = DigestGenerator(slack, prefs.config)

            # Fetch messages
            lookback_hours = prefs.get_lookback_hours()
            messages = slack.get_all_recent_messages(hours_back=lookback_hours)

            if not messages:
                print("No messages found.")
                return

            print(f"Analyzing {len(messages)} messages...")

            # Analyze messages
            scored_messages = analyzer.analyze_messages(messages)

            # Auto-adjust priorities
            analyzer.auto_adjust_priorities()

            # Get recommendations
            recommendations = analyzer.get_action_recommendations(scored_messages)

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

            # Save digest (use configured format)
            digest_gen.save_digest(digest_data)

            print(f"Digest generated: {digest_data['included_messages']} messages included")

    except Exception as e:
        print(f"Error generating digest: {e}")


def run_scheduler(prefs: PreferenceManager):
    """
    Run the scheduler.

    Args:
        prefs: PreferenceManager instance
    """
    scheduled_time = prefs.get_scheduler_time()
    scheduled_days = prefs.get_scheduler_days()

    # Map day numbers to schedule day methods
    day_map = {
        0: schedule.every().monday,
        1: schedule.every().tuesday,
        2: schedule.every().wednesday,
        3: schedule.every().thursday,
        4: schedule.every().friday,
        5: schedule.every().saturday,
        6: schedule.every().sunday
    }

    # Schedule for each configured day
    for day in scheduled_days:
        if day in day_map:
            day_map[day].at(scheduled_time).do(generate_scheduled_digest)

    print(f"Scheduler configured for: {scheduled_time} on days {scheduled_days}")

    # Run pending jobs
    while True:
        schedule.run_pending()
        time.sleep(60)  # Check every minute
