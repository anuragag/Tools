"""
Preference management for Slack Digest.

Handles loading, saving, and modifying user preferences.
"""

import yaml
from typing import Dict, Any, List, Optional
from pathlib import Path


class PreferenceManager:
    """Manages user preferences and configuration."""

    def __init__(self, config_path: str = "config.yaml"):
        """
        Initialize preference manager.

        Args:
            config_path: Path to config file
        """
        self.config_path = Path(config_path)
        self.config = self._load_config()

    def _load_config(self) -> Dict[str, Any]:
        """Load configuration from file."""
        if not self.config_path.exists():
            # Try to copy from example
            example_path = Path("config.yaml.example")
            if example_path.exists():
                import shutil
                shutil.copy(example_path, self.config_path)
                print(f"Created config file from example: {self.config_path}")
            else:
                raise FileNotFoundError(
                    f"Config file not found: {self.config_path}\n"
                    "Please copy config.yaml.example to config.yaml and configure it."
                )

        with open(self.config_path, 'r') as f:
            return yaml.safe_load(f) or {}

    def save_config(self):
        """Save configuration to file."""
        with open(self.config_path, 'w') as f:
            yaml.safe_dump(self.config, f, default_flow_style=False, sort_keys=False)

    def get(self, key: str, default: Any = None) -> Any:
        """
        Get a configuration value.

        Args:
            key: Config key (supports dot notation, e.g., 'slack.token')
            default: Default value if key not found

        Returns:
            Configuration value
        """
        keys = key.split('.')
        value = self.config

        for k in keys:
            if isinstance(value, dict) and k in value:
                value = value[k]
            else:
                return default

        return value

    def set(self, key: str, value: Any):
        """
        Set a configuration value.

        Args:
            key: Config key (supports dot notation)
            value: Value to set
        """
        keys = key.split('.')
        config = self.config

        for k in keys[:-1]:
            if k not in config:
                config[k] = {}
            config = config[k]

        config[keys[-1]] = value
        self.save_config()

    def get_slack_token(self) -> str:
        """Get Slack OAuth token."""
        token = self.get('slack.token')
        if not token or token == 'YOUR_SLACK_TOKEN_HERE':
            raise ValueError(
                "Slack token not configured. Please set 'slack.token' in config.yaml"
            )
        return token

    def get_user_id(self) -> Optional[str]:
        """Get Slack user ID."""
        return self.get('slack.user_id')

    def get_lookback_hours(self) -> int:
        """Get lookback period in hours."""
        return self.get('digest.lookback_hours', 24)

    def get_min_score(self) -> float:
        """Get minimum importance score for digest inclusion."""
        return self.get('digest.min_score', 30)

    def get_max_messages(self) -> int:
        """Get maximum messages in digest."""
        return self.get('digest.max_messages', 50)

    def get_database_path(self) -> str:
        """Get database path."""
        return self.get('database.path', 'slack_digest.db')

    def get_output_format(self) -> str:
        """Get output format."""
        return self.get('output.format', 'console')

    def get_html_output_path(self) -> str:
        """Get HTML output path."""
        return self.get('output.html_output', 'digest.html')

    def get_markdown_output_path(self) -> str:
        """Get Markdown output path."""
        return self.get('output.markdown_output', 'digest.md')

    def should_show_recommendations(self) -> bool:
        """Check if recommendations should be shown."""
        return self.get('output.show_recommendations', True)

    def is_scheduler_enabled(self) -> bool:
        """Check if scheduler is enabled."""
        return self.get('scheduler.enabled', False)

    def get_scheduler_time(self) -> str:
        """Get scheduled digest time."""
        return self.get('scheduler.time', '09:00')

    def get_scheduler_days(self) -> List[int]:
        """Get days to run scheduled digest."""
        return self.get('scheduler.days', [0, 1, 2, 3, 4])

    def get_scoring_weights(self) -> Dict[str, int]:
        """Get scoring weights."""
        return self.get('scoring', {
            'direct_mention_weight': 25,
            'thread_participation_weight': 20,
            'sender_priority_weight': 20,
            'channel_priority_weight': 15,
            'keyword_match_weight': 10,
            'recency_weight': 10
        })

    def is_learning_enabled(self) -> bool:
        """Check if learning is enabled."""
        return self.get('learning.enabled', True)

    def get_min_interactions_for_vip(self) -> int:
        """Get minimum interactions to auto-promote to VIP."""
        return self.get('learning.min_interactions_for_vip', 5)

    def get_learning_window_days(self) -> int:
        """Get learning window in days."""
        return self.get('learning.learning_window_days', 30)

    def add_vip_user(self, user_id: str):
        """
        Add a user to VIP list in config.

        Args:
            user_id: Slack user ID
        """
        vip_users = self.get('vip_users', [])
        if user_id not in vip_users:
            vip_users.append(user_id)
            self.set('vip_users', vip_users)

    def remove_vip_user(self, user_id: str):
        """
        Remove a user from VIP list in config.

        Args:
            user_id: Slack user ID
        """
        vip_users = self.get('vip_users', [])
        if user_id in vip_users:
            vip_users.remove(user_id)
            self.set('vip_users', vip_users)

    def add_keyword(self, keyword: str):
        """
        Add a keyword to config.

        Args:
            keyword: Keyword to track
        """
        keywords = self.get('keywords', [])
        if keyword not in keywords:
            keywords.append(keyword)
            self.set('keywords', keywords)

    def remove_keyword(self, keyword: str):
        """
        Remove a keyword from config.

        Args:
            keyword: Keyword to remove
        """
        keywords = self.get('keywords', [])
        if keyword in keywords:
            keywords.remove(keyword)
            self.set('keywords', keywords)

    def get_keywords(self) -> List[str]:
        """Get all keywords."""
        return self.get('keywords', [])

    def set_channel_priority(self, channel_name: str, priority: str):
        """
        Set channel priority in config.

        Args:
            channel_name: Channel name
            priority: Priority level (ignore, low, medium, high)
        """
        priorities = self.get('channel_priorities', {})
        priorities[channel_name] = priority
        self.set('channel_priorities', priorities)

    def get_channel_priority_from_config(self, channel_name: str) -> Optional[str]:
        """Get channel priority from config."""
        priorities = self.get('channel_priorities', {})
        return priorities.get(channel_name, priorities.get('default', 'medium'))

    def validate(self) -> List[str]:
        """
        Validate configuration.

        Returns:
            List of validation errors (empty if valid)
        """
        errors = []

        # Check Slack token
        token = self.get('slack.token')
        if not token or token == 'YOUR_SLACK_TOKEN_HERE':
            errors.append("Slack token not configured")

        # Check scoring weights sum to 100
        weights = self.get_scoring_weights()
        weight_sum = sum(weights.values())
        if weight_sum != 100:
            errors.append(f"Scoring weights must sum to 100 (current: {weight_sum})")

        # Check valid priorities
        valid_priorities = ['ignore', 'low', 'medium', 'high']
        default_priority = self.get('channel_priorities.default', 'medium')
        if default_priority not in valid_priorities:
            errors.append(f"Invalid default channel priority: {default_priority}")

        return errors

    def show_config(self) -> str:
        """
        Get a human-readable representation of current config.

        Returns:
            Formatted config string
        """
        lines = []
        lines.append("=== Slack Digest Configuration ===\n")

        lines.append("Slack:")
        lines.append(f"  Token: {'*' * 20} (configured)" if self.get('slack.token') else "  Token: NOT CONFIGURED")
        lines.append(f"  User ID: {self.get_user_id() or 'auto-detect'}")

        lines.append("\nDigest Settings:")
        lines.append(f"  Lookback: {self.get_lookback_hours()} hours")
        lines.append(f"  Min Score: {self.get_min_score()}")
        lines.append(f"  Max Messages: {self.get_max_messages()}")

        lines.append("\nOutput:")
        lines.append(f"  Format: {self.get_output_format()}")
        lines.append(f"  Show Recommendations: {self.should_show_recommendations()}")

        lines.append("\nLearning:")
        lines.append(f"  Enabled: {self.is_learning_enabled()}")
        lines.append(f"  Min Interactions for VIP: {self.get_min_interactions_for_vip()}")
        lines.append(f"  Learning Window: {self.get_learning_window_days()} days")

        lines.append("\nScheduler:")
        lines.append(f"  Enabled: {self.is_scheduler_enabled()}")
        lines.append(f"  Time: {self.get_scheduler_time()}")
        lines.append(f"  Days: {self.get_scheduler_days()}")

        keywords = self.get_keywords()
        lines.append(f"\nKeywords ({len(keywords)}):")
        for kw in keywords[:10]:  # Show first 10
            lines.append(f"  - {kw}")
        if len(keywords) > 10:
            lines.append(f"  ... and {len(keywords) - 10} more")

        return "\n".join(lines)
