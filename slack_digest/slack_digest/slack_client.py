"""
Slack API client for fetching messages and user data.
"""

from typing import List, Dict, Any, Optional
from datetime import datetime, timedelta
from slack_sdk import WebClient
from slack_sdk.errors import SlackApiError
import time


class SlackClient:
    """Wrapper around Slack SDK for digest-specific operations."""

    def __init__(self, token: str, user_id: Optional[str] = None):
        """
        Initialize Slack client.

        Args:
            token: Slack OAuth token
            user_id: Your Slack user ID (auto-detected if not provided)
        """
        self.client = WebClient(token=token)
        self.user_id = user_id or self._get_user_id()
        self.user_cache: Dict[str, Dict[str, Any]] = {}
        self.channel_cache: Dict[str, Dict[str, Any]] = {}

    def _get_user_id(self) -> str:
        """Auto-detect the authenticated user's ID."""
        try:
            response = self.client.auth_test()
            return response['user_id']
        except SlackApiError as e:
            raise Exception(f"Failed to authenticate: {e.response['error']}")

    def get_user_info(self, user_id: str) -> Dict[str, Any]:
        """
        Get user information (cached).

        Args:
            user_id: Slack user ID

        Returns:
            User information dict
        """
        if user_id in self.user_cache:
            return self.user_cache[user_id]

        try:
            response = self.client.users_info(user=user_id)
            user_info = {
                'id': user_id,
                'name': response['user']['name'],
                'real_name': response['user'].get('real_name', ''),
                'display_name': response['user']['profile'].get('display_name', ''),
                'is_bot': response['user'].get('is_bot', False)
            }
            self.user_cache[user_id] = user_info
            return user_info
        except SlackApiError as e:
            return {
                'id': user_id,
                'name': user_id,
                'real_name': '',
                'display_name': '',
                'is_bot': False
            }

    def get_channel_info(self, channel_id: str) -> Dict[str, Any]:
        """
        Get channel information (cached).

        Args:
            channel_id: Slack channel ID

        Returns:
            Channel information dict
        """
        if channel_id in self.channel_cache:
            return self.channel_cache[channel_id]

        try:
            # Try public channel first
            response = self.client.conversations_info(channel=channel_id)
            channel_info = {
                'id': channel_id,
                'name': response['channel']['name'],
                'is_private': response['channel'].get('is_private', False),
                'is_im': response['channel'].get('is_im', False),
                'is_mpim': response['channel'].get('is_mpim', False)
            }
            self.channel_cache[channel_id] = channel_info
            return channel_info
        except SlackApiError:
            return {
                'id': channel_id,
                'name': channel_id,
                'is_private': True,
                'is_im': False,
                'is_mpim': False
            }

    def get_channels(self) -> List[Dict[str, Any]]:
        """
        Get all channels the user is a member of.

        Returns:
            List of channel information dicts
        """
        channels = []

        try:
            # Public and private channels
            response = self.client.conversations_list(
                types="public_channel,private_channel",
                exclude_archived=True
            )

            for channel in response['channels']:
                if channel.get('is_member'):
                    channels.append({
                        'id': channel['id'],
                        'name': channel['name'],
                        'is_private': channel.get('is_private', False),
                        'is_im': False,
                        'is_mpim': False
                    })

            # DMs and group DMs
            response = self.client.conversations_list(
                types="im,mpim",
                exclude_archived=True
            )

            for channel in response['channels']:
                channels.append({
                    'id': channel['id'],
                    'name': self._get_dm_name(channel),
                    'is_private': True,
                    'is_im': channel.get('is_im', False),
                    'is_mpim': channel.get('is_mpim', False)
                })

        except SlackApiError as e:
            print(f"Error fetching channels: {e.response['error']}")

        return channels

    def _get_dm_name(self, channel: Dict[str, Any]) -> str:
        """Get a readable name for a DM channel."""
        if channel.get('is_im'):
            user_id = channel.get('user')
            if user_id:
                user_info = self.get_user_info(user_id)
                return f"DM: {user_info.get('display_name') or user_info.get('name')}"
        return channel.get('name', channel['id'])

    def get_messages(self, channel_id: str, hours_back: int = 24,
                    limit: int = 100) -> List[Dict[str, Any]]:
        """
        Get recent messages from a channel.

        Args:
            channel_id: Channel to fetch from
            hours_back: How many hours back to look
            limit: Maximum messages to fetch

        Returns:
            List of message dicts
        """
        oldest_ts = (datetime.now() - timedelta(hours=hours_back)).timestamp()
        messages = []

        try:
            response = self.client.conversations_history(
                channel=channel_id,
                oldest=str(oldest_ts),
                limit=limit
            )

            for msg in response['messages']:
                # Skip messages from the user themselves
                if msg.get('user') == self.user_id:
                    continue

                # Skip bot messages unless they mention the user
                if msg.get('bot_id') and not self._contains_user_mention(msg.get('text', '')):
                    continue

                messages.append(self._parse_message(msg, channel_id))

        except SlackApiError as e:
            print(f"Error fetching messages from {channel_id}: {e.response['error']}")

        return messages

    def get_thread_replies(self, channel_id: str, thread_ts: str) -> List[Dict[str, Any]]:
        """
        Get replies in a thread.

        Args:
            channel_id: Channel ID
            thread_ts: Thread timestamp

        Returns:
            List of reply message dicts
        """
        replies = []

        try:
            response = self.client.conversations_replies(
                channel=channel_id,
                ts=thread_ts
            )

            for msg in response['messages'][1:]:  # Skip the parent message
                replies.append(self._parse_message(msg, channel_id))

        except SlackApiError as e:
            print(f"Error fetching thread replies: {e.response['error']}")

        return replies

    def _parse_message(self, msg: Dict[str, Any], channel_id: str) -> Dict[str, Any]:
        """Parse a raw Slack message into our format."""
        return {
            'id': f"{channel_id}_{msg['ts']}",
            'ts': msg['ts'],
            'timestamp': float(msg['ts']),
            'channel_id': channel_id,
            'user_id': msg.get('user'),
            'text': msg.get('text', ''),
            'thread_ts': msg.get('thread_ts'),
            'reply_count': msg.get('reply_count', 0),
            'reactions': msg.get('reactions', []),
            'is_thread_parent': 'thread_ts' in msg and msg['thread_ts'] == msg['ts'],
            'attachments': msg.get('attachments', []),
            'files': msg.get('files', [])
        }

    def _contains_user_mention(self, text: str) -> bool:
        """Check if text contains a mention of the user."""
        return f"<@{self.user_id}>" in text

    def is_user_mentioned(self, message: Dict[str, Any]) -> bool:
        """
        Check if the user is mentioned in a message.

        Args:
            message: Message dict

        Returns:
            True if user is mentioned
        """
        return self._contains_user_mention(message.get('text', ''))

    def is_user_in_thread(self, channel_id: str, thread_ts: str) -> bool:
        """
        Check if user has participated in a thread.

        Args:
            channel_id: Channel ID
            thread_ts: Thread timestamp

        Returns:
            True if user has replied in thread
        """
        try:
            response = self.client.conversations_replies(
                channel=channel_id,
                ts=thread_ts
            )

            for msg in response['messages']:
                if msg.get('user') == self.user_id:
                    return True

        except SlackApiError:
            pass

        return False

    def has_user_reacted(self, message: Dict[str, Any]) -> bool:
        """
        Check if user has reacted to a message.

        Args:
            message: Message dict

        Returns:
            True if user has reacted
        """
        for reaction in message.get('reactions', []):
            if self.user_id in reaction.get('users', []):
                return True
        return False

    def get_all_recent_messages(self, hours_back: int = 24,
                                max_messages_per_channel: int = 100) -> List[Dict[str, Any]]:
        """
        Get recent messages from all channels.

        Args:
            hours_back: How many hours back to look
            max_messages_per_channel: Max messages per channel

        Returns:
            List of all messages across channels
        """
        all_messages = []
        channels = self.get_channels()

        for channel in channels:
            messages = self.get_messages(
                channel['id'],
                hours_back=hours_back,
                limit=max_messages_per_channel
            )
            all_messages.extend(messages)

            # Rate limiting - be nice to Slack API
            time.sleep(0.5)

        return all_messages

    def get_permalink(self, channel_id: str, message_ts: str) -> str:
        """
        Get permalink URL for a message.

        Args:
            channel_id: Channel ID
            message_ts: Message timestamp

        Returns:
            Permalink URL
        """
        try:
            response = self.client.chat_getPermalink(
                channel=channel_id,
                message_ts=message_ts
            )
            return response['permalink']
        except SlackApiError:
            return f"https://slack.com/app_redirect?channel={channel_id}&message_ts={message_ts}"
