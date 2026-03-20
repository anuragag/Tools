# Spreadsheet → Google Calendar Sync

A Node.js tool that reads a season-schedule spreadsheet (where columns are weekends and rows are leagues/categories) and creates Google Calendar events for each non-empty cell. Runs externally — no Apps Script needed.

## Setup

### 1. Install dependencies

```bash
cd spreadsheet-calendar-sync
npm install
```

### 2. Create Google Cloud credentials

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a project (or use an existing one)
3. Enable the **Google Sheets API** and **Google Calendar API**
4. Go to **APIs & Services → Credentials**
5. Click **Create Credentials → OAuth 2.0 Client ID**
6. Application type: **Desktop app**
7. Download the JSON file and save it as `credentials.json` in the project root

### 3. Authorize

```bash
npm run auth
```

This opens a URL in your terminal. Visit it in a browser, authorize, and a `token.json` will be saved locally. You only need to do this once.

### 4. Configure

Edit `config.json`:

```json
{
  "spreadsheetId": "YOUR_SPREADSHEET_ID",
  "sheetNames": [],
  "rowsToSync": [
    { "row": 4, "label": "Tournament" },
    { "row": 5, "label": "PCSSL Dragons" },
    { "row": 6, "label": "PCSSL Spirit" },
    { "row": 7, "label": "NorCal Spirit" },
    { "row": 8, "label": "AYSO Core" }
  ],
  "calendarId": "primary",
  "seasonYear": 2026,
  "allDayEvents": true,
  "defaultStartHour": 9,
  "syncTag": "[spreadsheet-calendar-sync]",
  "dryRun": false
}
```

| Setting | Description |
|---------|-------------|
| `spreadsheetId` | The ID from the Google Sheet URL (between `/d/` and `/edit`) |
| `sheetNames` | Array of sheet names to read. Empty `[]` = first sheet |
| `rowsToSync` | Array of `{ row, label }` objects. Empty `[]` = auto-detect all rows with a column-A label |
| `calendarId` | `"primary"` for default calendar, or a specific calendar ID |
| `seasonYear` | Year to use when parsing date headers (e.g. `2026`) |
| `allDayEvents` | `true` for all-day events, `false` for timed events |
| `defaultStartHour` | Hour (0-23) for timed events |
| `dryRun` | `true` to preview without creating events |

### 5. Run

```bash
# Sync spreadsheet to calendar
npm run sync

# Preview without making changes
npm run dry-run

# Delete all previously synced events
npm run delete
```

### 6. Schedule with Claude Code `/loop`

To auto-sync every 30 minutes:

```
/loop 30m npm run sync --prefix /path/to/spreadsheet-calendar-sync
```

## How it works

1. Reads the header row to parse weekend date ranges (e.g. "FEB 7-8", "FEB 28-MAR 1") into actual dates
2. For each configured row, reads every cell across the weekend columns
3. Non-empty cells become calendar events titled `{label}: {cell value}`
4. Events are tagged in the description with `[spreadsheet-calendar-sync]` so the script can identify and manage them on re-sync
5. On re-sync, stale events (no longer in the spreadsheet) are deleted, existing events are kept, and new ones are created

## Expected spreadsheet format

```
| Category          | FEB 7-8 | FEB 14-15 | FEB 21-22 | ... |
|-------------------|---------|-----------|-----------|-----|
| Weekend Dates     | FEB 7-8 | FEB 14-15 | FEB 21-22 | ... |
| Holidays          |         |           | Break     | ... |
| Tournaments       |         |           |           | ... |
| League A Games    |         |           | 1         | ... |
| League B Games    |         | Scrimmage | 1         | ... |
```

- **Row 1**: Weekend date-range headers (required)
- **Column A**: Row labels / category names
- **Cells**: Game numbers, event names, or any text (empty = no event)

## Legacy Apps Script version

The original Apps Script files (`Code.gs`, `Config.gs`) are kept for reference. The Node.js version in `src/` is the recommended approach.
