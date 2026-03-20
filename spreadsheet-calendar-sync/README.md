# Spreadsheet → Google Calendar Sync

An Apps Script that reads a season-schedule spreadsheet (where columns are weekends and rows are leagues/categories) and creates Google Calendar events for each non-empty cell.

## Setup

1. Open your schedule spreadsheet in Google Sheets
2. Go to **Extensions → Apps Script**
3. Delete the default `Code.gs` content
4. Create two files in the Apps Script editor:
   - `Config.gs` — paste the contents of `Config.gs` from this repo
   - `Code.gs` — paste the contents of `Code.gs` from this repo
5. Edit `Config.gs` to set your preferences (see below)
6. Save, then run `syncToCalendar` from the editor (or use the menu)
7. On first run, authorize the script when prompted

## Configuration (`Config.gs`)

| Setting | Description |
|---------|-------------|
| `SPREADSHEET_URL` | Full URL of the Google Sheet |
| `SHEET_NAMES` | Array of sheet names to read, e.g. `["Spring 2026"]`. Empty `[]` = first sheet |
| `ROWS_TO_SYNC` | Array of `{ row: N, label: "Name" }` objects. Empty `[]` = auto-detect all rows with a column-A label |
| `CALENDAR_ID` | `"primary"` for default calendar, or a specific calendar ID |
| `SEASON_YEAR` | Year to use when parsing date headers (e.g. `2026`) |
| `ALL_DAY_EVENTS` | `true` for all-day events, `false` for timed events |
| `DEFAULT_START_HOUR` | Hour (0-23) for timed events |
| `DRY_RUN` | `true` to preview without creating events (check Logs) |

### Example: Sync only specific rows

```js
ROWS_TO_SYNC: [
  { row: 4, label: "Tournament" },
  { row: 5, label: "PCSSL Dragons" },
  { row: 6, label: "PCSSL Spirit" },
  { row: 7, label: "NorCal Spirit" },
  { row: 8, label: "AYSO Core" },
],
```

### Example: Sync a different spreadsheet

```js
SPREADSHEET_URL: "https://docs.google.com/spreadsheets/d/YOUR_SHEET_ID/edit",
SHEET_NAMES: ["Schedule", "Tournaments"],
```

## Usage

### From the menu
After setup, reload the spreadsheet. A **Calendar Sync** menu appears with:
- **Sync to Calendar** — create/update events
- **Delete All Synced Events** — remove everything this script created
- **Dry Run (preview)** — log what would happen without modifying the calendar

### From the Apps Script editor
Run `syncToCalendar()` directly. Check **View → Logs** for output.

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
