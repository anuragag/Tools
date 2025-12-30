# Slack Digest Tool

An intelligent Slack digest tool that helps you manage Slack overload by generating daily reports of missed messages with smart prioritization and action recommendations.

## Features

- **Smart Message Prioritization**: Automatically scores messages based on:
  - Direct mentions and threads you're involved in
  - Your interaction patterns (who you respond to frequently)
  - Channel importance based on your activity
  - Keywords and projects you care about
  - Message urgency indicators

- **Daily Digest Reports**: Get a consolidated view of:
  - High-priority messages you missed
  - Recommended actions
  - Summary of channel activity
  - Trending topics in your workspace

- **Customizable Preferences**: Manual control over:
  - Channel priorities (mute, low, medium, high)
  - User priorities (VIP list)
  - Keywords and project tags to track
  - Digest delivery schedule

- **Learning System**: Continuously learns from your behavior:
  - Tracks who you respond to
  - Identifies your active projects
  - Adapts to your communication patterns

## Installation

1. Clone this repository
2. Install dependencies:
```bash
pip install -r requirements.txt
```

3. Set up your Slack app and get API credentials:
   - Go to https://api.slack.com/apps
   - Create a new app
   - Add the following OAuth scopes:
     - `channels:history`
     - `channels:read`
     - `users:read`
     - `groups:history`
     - `groups:read`
     - `im:history`
     - `mpim:history`
     - `reactions:read`
   - Install the app to your workspace
   - Copy the OAuth token

4. Configure the tool:
```bash
cp config.yaml.example config.yaml
# Edit config.yaml with your Slack token and preferences
```

## Usage

### Generate a Digest

```bash
python -m slack_digest generate
```

### View Configuration

```bash
python -m slack_digest config show
```

### Set Channel Priority

```bash
python -m slack_digest config set-channel "#general" --priority low
python -m slack_digest config set-channel "#engineering" --priority high
```

### Add VIP Users

```bash
python -m slack_digest config add-vip "@boss" "@teamlead"
```

### Add Keywords to Track

```bash
python -m slack_digest config add-keywords "product-launch" "Q1-goals" "security"
```

### Schedule Daily Digest

```bash
python -m slack_digest schedule --time "09:00"
```

### View Interaction Statistics

```bash
python -m slack_digest stats
```

## How It Works

### Importance Scoring Algorithm

Each message receives a score (0-100) based on:

1. **Direct Mentions** (25 points): Messages that @mention you
2. **Thread Participation** (20 points): Replies in threads you're active in
3. **Sender Priority** (20 points): Messages from people you frequently interact with
4. **Channel Priority** (15 points): Messages from high-priority channels
5. **Keyword Match** (10 points): Messages containing your tracked keywords
6. **Recency** (10 points): More recent messages score higher

### Learning from Your Behavior

The tool tracks:
- Response frequency to different users
- Time spent in different channels
- Keywords in messages you react to or reply to
- Threads you participate in

This data improves prioritization over time.

## Configuration

See `config.yaml.example` for all available options.

## Data Storage

All data is stored locally in SQLite database (`slack_digest.db`):
- Interaction history
- User preferences
- Message metadata (no message content is stored for privacy)

## Privacy

- Message content is analyzed but not permanently stored
- Only metadata (message IDs, timestamps, scores) is retained
- All data stays on your local machine

## License

MIT
