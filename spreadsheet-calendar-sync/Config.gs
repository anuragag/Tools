// ============================================================
// CONFIGURATION — Edit this section to control what gets synced
// ============================================================

const CONFIG = {
  // ---- Spreadsheet Source ----
  // The full URL or just the spreadsheet ID
  SPREADSHEET_URL: "https://docs.google.com/spreadsheets/d/1Y9aqzdBuNHL88z5ygOJtly6bKB2HZgonf-eHvGia6yw/edit",

  // ---- Which sheets to read (by name) ----
  // Leave empty [] to use the first sheet only
  SHEET_NAMES: [],

  // ---- Which rows to sync (1-indexed, matching the spreadsheet) ----
  // Each entry maps a row number to a label for the calendar event.
  // Example: row 5 contains "PCSSL – Dragons Games"
  //
  // If left empty, ALL rows that have column-A text will be synced,
  // using the column-A value as the event prefix.
  ROWS_TO_SYNC: [
    // { row: 4, label: "Tournament" },
    // { row: 5, label: "PCSSL Dragons" },
    // { row: 6, label: "PCSSL Spirit" },
    // { row: 7, label: "NorCal Spirit" },
    // { row: 8, label: "AYSO Core" },
  ],

  // ---- Calendar to write events to ----
  // Use "primary" for your default calendar, or a calendar ID like
  // "abc123@group.calendar.google.com"
  CALENDAR_ID: "primary",

  // ---- Year for the schedule ----
  // The spreadsheet dates don't include a year, so set it here.
  SEASON_YEAR: 2026,

  // ---- Event defaults ----
  // If true, events are created as all-day events on the Saturday of
  // each weekend range. If false, they're created as 1-hour events
  // at DEFAULT_START_HOUR.
  ALL_DAY_EVENTS: true,
  DEFAULT_START_HOUR: 9, // used only when ALL_DAY_EVENTS = false

  // ---- Duplicate handling ----
  // A tag added to event descriptions so the script can find and
  // update/delete its own events on re-sync.
  SYNC_TAG: "[spreadsheet-calendar-sync]",

  // ---- Dry-run mode ----
  // Set to true to log what would happen without creating events.
  DRY_RUN: false,
};
