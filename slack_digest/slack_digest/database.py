"""
Database management for Slack Digest Tool.

Handles storage of:
- User preferences (channel/user priorities, keywords)
- Interaction history
- Message metadata
"""

import sqlite3
from datetime import datetime
from typing import List, Dict, Optional, Any
import json


class Database:
    """Manages SQLite database for Slack digest data."""

    def __init__(self, db_path: str = "slack_digest.db"):
        """
        Initialize database connection.

        Args:
            db_path: Path to SQLite database file
        """
        self.db_path = db_path
        self.conn = sqlite3.connect(db_path)
        self.conn.row_factory = sqlite3.Row
        self._init_tables()

    def _init_tables(self):
        """Create database tables if they don't exist."""
        cursor = self.conn.cursor()

        # Channel preferences
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS channel_preferences (
                channel_id TEXT PRIMARY KEY,
                channel_name TEXT,
                priority TEXT CHECK(priority IN ('ignore', 'low', 'medium', 'high')),
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)

        # User preferences (VIP list)
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS user_preferences (
                user_id TEXT PRIMARY KEY,
                user_name TEXT,
                priority TEXT CHECK(priority IN ('low', 'medium', 'high', 'vip')),
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)

        # Keywords to track
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS keywords (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                keyword TEXT UNIQUE NOT NULL,
                category TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)

        # Interaction history
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS interactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id TEXT NOT NULL,
                user_name TEXT,
                channel_id TEXT,
                interaction_type TEXT CHECK(interaction_type IN ('reply', 'reaction', 'mention')),
                timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)

        # Message metadata (for deduplication and tracking)
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS message_metadata (
                message_id TEXT PRIMARY KEY,
                channel_id TEXT NOT NULL,
                timestamp REAL NOT NULL,
                importance_score REAL,
                included_in_digest BOOLEAN DEFAULT 0,
                digest_date DATE,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)

        # Digest history
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS digest_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                digest_date DATE NOT NULL,
                message_count INTEGER,
                avg_importance REAL,
                generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)

        # Create indexes
        cursor.execute("""
            CREATE INDEX IF NOT EXISTS idx_interactions_user
            ON interactions(user_id, timestamp)
        """)

        cursor.execute("""
            CREATE INDEX IF NOT EXISTS idx_interactions_channel
            ON interactions(channel_id, timestamp)
        """)

        cursor.execute("""
            CREATE INDEX IF NOT EXISTS idx_message_metadata_timestamp
            ON message_metadata(timestamp, included_in_digest)
        """)

        self.conn.commit()

    # Channel Preferences
    def set_channel_priority(self, channel_id: str, channel_name: str, priority: str):
        """Set priority for a channel."""
        cursor = self.conn.cursor()
        cursor.execute("""
            INSERT INTO channel_preferences (channel_id, channel_name, priority, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(channel_id) DO UPDATE SET
                channel_name = excluded.channel_name,
                priority = excluded.priority,
                updated_at = excluded.updated_at
        """, (channel_id, channel_name, priority, datetime.now()))
        self.conn.commit()

    def get_channel_priority(self, channel_id: str) -> Optional[str]:
        """Get priority for a channel."""
        cursor = self.conn.cursor()
        cursor.execute(
            "SELECT priority FROM channel_preferences WHERE channel_id = ?",
            (channel_id,)
        )
        row = cursor.fetchone()
        return row['priority'] if row else None

    def get_all_channel_preferences(self) -> List[Dict[str, Any]]:
        """Get all channel preferences."""
        cursor = self.conn.cursor()
        cursor.execute("SELECT * FROM channel_preferences ORDER BY priority DESC, channel_name")
        return [dict(row) for row in cursor.fetchall()]

    # User Preferences
    def set_user_priority(self, user_id: str, user_name: str, priority: str):
        """Set priority for a user."""
        cursor = self.conn.cursor()
        cursor.execute("""
            INSERT INTO user_preferences (user_id, user_name, priority, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(user_id) DO UPDATE SET
                user_name = excluded.user_name,
                priority = excluded.priority,
                updated_at = excluded.updated_at
        """, (user_id, user_name, priority, datetime.now()))
        self.conn.commit()

    def get_user_priority(self, user_id: str) -> Optional[str]:
        """Get priority for a user."""
        cursor = self.conn.cursor()
        cursor.execute(
            "SELECT priority FROM user_preferences WHERE user_id = ?",
            (user_id,)
        )
        row = cursor.fetchone()
        return row['priority'] if row else None

    def get_all_user_preferences(self) -> List[Dict[str, Any]]:
        """Get all user preferences."""
        cursor = self.conn.cursor()
        cursor.execute("SELECT * FROM user_preferences ORDER BY priority DESC, user_name")
        return [dict(row) for row in cursor.fetchall()]

    def get_vip_users(self) -> List[str]:
        """Get list of VIP user IDs."""
        cursor = self.conn.cursor()
        cursor.execute("SELECT user_id FROM user_preferences WHERE priority = 'vip'")
        return [row['user_id'] for row in cursor.fetchall()]

    # Keywords
    def add_keyword(self, keyword: str, category: Optional[str] = None):
        """Add a keyword to track."""
        cursor = self.conn.cursor()
        try:
            cursor.execute(
                "INSERT INTO keywords (keyword, category) VALUES (?, ?)",
                (keyword.lower(), category)
            )
            self.conn.commit()
        except sqlite3.IntegrityError:
            pass  # Keyword already exists

    def remove_keyword(self, keyword: str):
        """Remove a keyword."""
        cursor = self.conn.cursor()
        cursor.execute("DELETE FROM keywords WHERE keyword = ?", (keyword.lower(),))
        self.conn.commit()

    def get_all_keywords(self) -> List[str]:
        """Get all tracked keywords."""
        cursor = self.conn.cursor()
        cursor.execute("SELECT keyword FROM keywords ORDER BY keyword")
        return [row['keyword'] for row in cursor.fetchall()]

    # Interaction History
    def record_interaction(self, user_id: str, user_name: str,
                          channel_id: str, interaction_type: str):
        """Record an interaction with a user."""
        cursor = self.conn.cursor()
        cursor.execute("""
            INSERT INTO interactions (user_id, user_name, channel_id, interaction_type, timestamp)
            VALUES (?, ?, ?, ?, ?)
        """, (user_id, user_name, channel_id, interaction_type, datetime.now()))
        self.conn.commit()

    def get_interaction_frequency(self, user_id: str, days: int = 30) -> int:
        """Get number of interactions with a user in the last N days."""
        cursor = self.conn.cursor()
        cursor.execute("""
            SELECT COUNT(*) as count
            FROM interactions
            WHERE user_id = ?
            AND timestamp > datetime('now', '-' || ? || ' days')
        """, (user_id, days))
        row = cursor.fetchone()
        return row['count'] if row else 0

    def get_frequent_users(self, min_interactions: int = 5, days: int = 30) -> List[Dict[str, Any]]:
        """Get users you interact with frequently."""
        cursor = self.conn.cursor()
        cursor.execute("""
            SELECT user_id, user_name, COUNT(*) as interaction_count
            FROM interactions
            WHERE timestamp > datetime('now', '-' || ? || ' days')
            GROUP BY user_id
            HAVING COUNT(*) >= ?
            ORDER BY interaction_count DESC
        """, (days, min_interactions))
        return [dict(row) for row in cursor.fetchall()]

    def get_active_channels(self, days: int = 30) -> List[Dict[str, Any]]:
        """Get channels you're most active in."""
        cursor = self.conn.cursor()
        cursor.execute("""
            SELECT channel_id, COUNT(*) as interaction_count
            FROM interactions
            WHERE timestamp > datetime('now', '-' || ? || ' days')
            AND channel_id IS NOT NULL
            GROUP BY channel_id
            ORDER BY interaction_count DESC
        """, (days,))
        return [dict(row) for row in cursor.fetchall()]

    # Message Metadata
    def mark_message_processed(self, message_id: str, channel_id: str,
                               timestamp: float, importance_score: float,
                               included_in_digest: bool = False):
        """Mark a message as processed."""
        cursor = self.conn.cursor()
        digest_date = datetime.now().date() if included_in_digest else None
        cursor.execute("""
            INSERT INTO message_metadata
            (message_id, channel_id, timestamp, importance_score, included_in_digest, digest_date)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(message_id) DO UPDATE SET
                importance_score = excluded.importance_score,
                included_in_digest = excluded.included_in_digest,
                digest_date = excluded.digest_date
        """, (message_id, channel_id, timestamp, importance_score, included_in_digest, digest_date))
        self.conn.commit()

    def is_message_processed(self, message_id: str) -> bool:
        """Check if a message has been processed."""
        cursor = self.conn.cursor()
        cursor.execute(
            "SELECT 1 FROM message_metadata WHERE message_id = ?",
            (message_id,)
        )
        return cursor.fetchone() is not None

    # Digest History
    def record_digest(self, message_count: int, avg_importance: float):
        """Record digest generation."""
        cursor = self.conn.cursor()
        cursor.execute("""
            INSERT INTO digest_history (digest_date, message_count, avg_importance)
            VALUES (?, ?, ?)
        """, (datetime.now().date(), message_count, avg_importance))
        self.conn.commit()

    def get_digest_stats(self, days: int = 30) -> List[Dict[str, Any]]:
        """Get digest statistics."""
        cursor = self.conn.cursor()
        cursor.execute("""
            SELECT digest_date, message_count, avg_importance, generated_at
            FROM digest_history
            WHERE digest_date > date('now', '-' || ? || ' days')
            ORDER BY digest_date DESC
        """, (days,))
        return [dict(row) for row in cursor.fetchall()]

    def cleanup_old_data(self, days: int = 90):
        """Clean up old interaction and message data."""
        cursor = self.conn.cursor()

        # Keep interactions for learning window
        cursor.execute("""
            DELETE FROM interactions
            WHERE timestamp < datetime('now', '-' || ? || ' days')
        """, (days,))

        # Keep message metadata for digest window
        cursor.execute("""
            DELETE FROM message_metadata
            WHERE created_at < datetime('now', '-' || ? || ' days')
        """, (days,))

        self.conn.commit()

    def close(self):
        """Close database connection."""
        self.conn.close()

    def __enter__(self):
        """Context manager entry."""
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        """Context manager exit."""
        self.close()
