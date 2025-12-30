"""
Message importance analyzer.

Scores messages based on multiple factors:
- Direct mentions
- Thread participation
- Sender priority
- Channel priority
- Keyword matches
- Recency
"""

from typing import Dict, List, Any, Optional
from datetime import datetime
import re


class ImportanceAnalyzer:
    """Analyzes and scores message importance."""

    def __init__(self, config: Dict[str, Any], database, slack_client):
        """
        Initialize analyzer.

        Args:
            config: Configuration dict with scoring weights
            database: Database instance
            slack_client: SlackClient instance
        """
        self.config = config
        self.db = database
        self.slack = slack_client

        # Get scoring weights from config
        scoring = config.get('scoring', {})
        self.weights = {
            'direct_mention': scoring.get('direct_mention_weight', 25),
            'thread_participation': scoring.get('thread_participation_weight', 20),
            'sender_priority': scoring.get('sender_priority_weight', 20),
            'channel_priority': scoring.get('channel_priority_weight', 15),
            'keyword_match': scoring.get('keyword_match_weight', 10),
            'recency': scoring.get('recency_weight', 10)
        }

        # Cache for thread participation
        self._thread_participation_cache: Dict[str, bool] = {}

    def score_message(self, message: Dict[str, Any]) -> float:
        """
        Calculate importance score for a message.

        Args:
            message: Message dict

        Returns:
            Importance score (0-100)
        """
        scores = {
            'direct_mention': self._score_direct_mention(message),
            'thread_participation': self._score_thread_participation(message),
            'sender_priority': self._score_sender_priority(message),
            'channel_priority': self._score_channel_priority(message),
            'keyword_match': self._score_keyword_match(message),
            'recency': self._score_recency(message)
        }

        # Calculate weighted total
        total_score = sum(
            scores[factor] * (self.weights[factor] / 100.0)
            for factor in scores
        )

        return min(100.0, total_score)

    def _score_direct_mention(self, message: Dict[str, Any]) -> float:
        """Score based on direct mentions."""
        text = message.get('text', '').lower()

        # Check for @mention
        if self.slack.is_user_mentioned(message):
            return 100.0

        # Check for name mentions (less reliable)
        user_info = self.slack.get_user_info(self.slack.user_id)
        name_patterns = [
            user_info.get('name', '').lower(),
            user_info.get('display_name', '').lower(),
            user_info.get('real_name', '').lower()
        ]

        for name in name_patterns:
            if name and name in text:
                return 75.0

        return 0.0

    def _score_thread_participation(self, message: Dict[str, Any]) -> float:
        """Score based on thread participation."""
        # If it's a reply in a thread
        if message.get('thread_ts') and message['thread_ts'] != message['ts']:
            thread_key = f"{message['channel_id']}_{message['thread_ts']}"

            # Check cache first
            if thread_key in self._thread_participation_cache:
                return 100.0 if self._thread_participation_cache[thread_key] else 0.0

            # Check if user is in thread
            is_participating = self.slack.is_user_in_thread(
                message['channel_id'],
                message['thread_ts']
            )
            self._thread_participation_cache[thread_key] = is_participating

            return 100.0 if is_participating else 25.0

        # If it's a parent message with replies
        if message.get('is_thread_parent') and message.get('reply_count', 0) > 0:
            return 50.0  # Moderate importance for active threads

        return 0.0

    def _score_sender_priority(self, message: Dict[str, Any]) -> float:
        """Score based on sender priority."""
        sender_id = message.get('user_id')
        if not sender_id:
            return 0.0

        # Check manual VIP list
        user_priority = self.db.get_user_priority(sender_id)
        if user_priority == 'vip':
            return 100.0
        elif user_priority == 'high':
            return 85.0
        elif user_priority == 'medium':
            return 50.0
        elif user_priority == 'low':
            return 25.0

        # Check interaction frequency (learning)
        if self.config.get('learning', {}).get('enabled', True):
            learning_window = self.config.get('learning', {}).get('learning_window_days', 30)
            min_interactions = self.config.get('learning', {}).get('min_interactions_for_vip', 5)

            interaction_count = self.db.get_interaction_frequency(
                sender_id,
                days=learning_window
            )

            if interaction_count >= min_interactions * 2:
                return 100.0  # Very frequent contact
            elif interaction_count >= min_interactions:
                return 75.0   # Frequent contact
            elif interaction_count > 0:
                return 40.0   # Some contact

        return 25.0  # Default for unknown senders

    def _score_channel_priority(self, message: Dict[str, Any]) -> float:
        """Score based on channel priority."""
        channel_id = message.get('channel_id')
        if not channel_id:
            return 50.0

        # Check manual channel priority
        channel_priority = self.db.get_channel_priority(channel_id)
        if channel_priority == 'high':
            return 100.0
        elif channel_priority == 'medium':
            return 65.0
        elif channel_priority == 'low':
            return 30.0
        elif channel_priority == 'ignore':
            return 0.0

        # Use default from config
        default_priority = self.config.get('channel_priorities', {}).get('default', 'medium')
        if default_priority == 'high':
            return 100.0
        elif default_priority == 'medium':
            return 65.0
        elif default_priority == 'low':
            return 30.0

        return 65.0

    def _score_keyword_match(self, message: Dict[str, Any]) -> float:
        """Score based on keyword matches."""
        text = message.get('text', '').lower()
        keywords = self.db.get_all_keywords()

        if not keywords:
            return 0.0

        # Count keyword matches
        matches = 0
        for keyword in keywords:
            if keyword.lower() in text:
                matches += 1

        if matches == 0:
            return 0.0
        elif matches == 1:
            return 70.0
        else:
            return 100.0  # Multiple keyword matches

    def _score_recency(self, message: Dict[str, Any]) -> float:
        """Score based on message recency."""
        timestamp = message.get('timestamp', 0)
        now = datetime.now().timestamp()
        age_hours = (now - timestamp) / 3600.0

        # Linear decay over 24 hours
        lookback_hours = self.config.get('digest', {}).get('lookback_hours', 24)

        if age_hours <= 0:
            return 100.0
        elif age_hours >= lookback_hours:
            return 0.0
        else:
            # Newer messages score higher
            return 100.0 * (1 - (age_hours / lookback_hours))

    def analyze_messages(self, messages: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """
        Score all messages and add importance scores.

        Args:
            messages: List of message dicts

        Returns:
            Messages with 'importance_score' added and sorted by score
        """
        scored_messages = []

        for message in messages:
            score = self.score_message(message)
            message['importance_score'] = score
            message['score_breakdown'] = self._get_score_breakdown(message)
            scored_messages.append(message)

        # Sort by importance (highest first)
        scored_messages.sort(key=lambda m: m['importance_score'], reverse=True)

        return scored_messages

    def _get_score_breakdown(self, message: Dict[str, Any]) -> Dict[str, float]:
        """Get detailed score breakdown for a message."""
        return {
            'direct_mention': self._score_direct_mention(message),
            'thread_participation': self._score_thread_participation(message),
            'sender_priority': self._score_sender_priority(message),
            'channel_priority': self._score_channel_priority(message),
            'keyword_match': self._score_keyword_match(message),
            'recency': self._score_recency(message)
        }

    def get_action_recommendations(self, messages: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """
        Generate action recommendations based on message analysis.

        Args:
            messages: Scored messages

        Returns:
            List of recommendation dicts
        """
        recommendations = []

        # Group by type of action needed
        mentions = [m for m in messages if self._score_direct_mention(m) > 50]
        threads = [m for m in messages if self._score_thread_participation(m) > 50]
        vip = [m for m in messages if self._score_sender_priority(m) >= 85]

        if mentions:
            recommendations.append({
                'type': 'mentions',
                'priority': 'high',
                'count': len(mentions),
                'action': f"Respond to {len(mentions)} direct mention(s)",
                'messages': mentions[:5]  # Top 5
            })

        if threads:
            recommendations.append({
                'type': 'threads',
                'priority': 'medium',
                'count': len(threads),
                'action': f"Check {len(threads)} active thread(s) you're in",
                'messages': threads[:5]
            })

        if vip:
            vip_no_mention = [m for m in vip if m not in mentions]
            if vip_no_mention:
                recommendations.append({
                    'type': 'vip',
                    'priority': 'high',
                    'count': len(vip_no_mention),
                    'action': f"Review {len(vip_no_mention)} message(s) from important contacts",
                    'messages': vip_no_mention[:5]
                })

        return recommendations

    def learn_from_interaction(self, user_id: str, user_name: str,
                               channel_id: str, interaction_type: str):
        """
        Record an interaction for learning.

        Args:
            user_id: User interacted with
            user_name: User name
            channel_id: Channel where interaction occurred
            interaction_type: Type of interaction (reply, reaction, mention)
        """
        if self.config.get('learning', {}).get('enabled', True):
            self.db.record_interaction(user_id, user_name, channel_id, interaction_type)

    def auto_adjust_priorities(self):
        """
        Automatically adjust priorities based on learned behavior.
        """
        if not self.config.get('learning', {}).get('enabled', True):
            return

        learning_config = self.config.get('learning', {})
        min_interactions = learning_config.get('min_interactions_for_vip', 5)
        learning_window = learning_config.get('learning_window_days', 30)

        # Auto-promote frequent contacts to VIP
        frequent_users = self.db.get_frequent_users(
            min_interactions=min_interactions,
            days=learning_window
        )

        for user in frequent_users:
            # Only auto-promote if not manually set
            current_priority = self.db.get_user_priority(user['user_id'])
            if current_priority is None:
                self.db.set_user_priority(
                    user['user_id'],
                    user['user_name'],
                    'vip'
                )
