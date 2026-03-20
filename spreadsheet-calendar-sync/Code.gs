// ============================================================
// Spreadsheet → Google Calendar Sync
// ============================================================
//
// Reads a season-schedule spreadsheet where:
//   - Column A contains row labels (league names, categories, etc.)
//   - Columns B onwards are weekends with date-range headers
//     like "FEB 7-8", "MAR 28-29", "FEB 28-MAR 1"
//   - Cells contain game numbers, event names, or notes
//
// The script parses each weekend header into a real date, then
// creates Google Calendar events for every non-empty cell in the
// configured rows.
// ============================================================

// --------------- ENTRY POINTS ---------------

/**
 * Main function – run this to sync.
 */
function syncToCalendar() {
  const ss = SpreadsheetApp.openByUrl(CONFIG.SPREADSHEET_URL);
  const sheets = getTargetSheets_(ss);

  let totalCreated = 0;
  let totalDeleted = 0;

  for (const sheet of sheets) {
    Logger.log("Processing sheet: " + sheet.getName());
    const result = processSheet_(sheet);
    totalCreated += result.created;
    totalDeleted += result.deleted;
  }

  Logger.log(
    "Sync complete. Created: " +
      totalCreated +
      ", Deleted (stale): " +
      totalDeleted
  );
  if (CONFIG.DRY_RUN) {
    Logger.log("(DRY RUN — no events were actually modified)");
  }
}

/**
 * Deletes ALL events previously created by this script.
 */
function deleteAllSyncedEvents() {
  const cal = CalendarApp.getCalendarById(CONFIG.CALENDAR_ID);
  if (!cal) throw new Error("Calendar not found: " + CONFIG.CALENDAR_ID);

  // Search a wide window (current year ± 1)
  const start = new Date(CONFIG.SEASON_YEAR - 1, 0, 1);
  const end = new Date(CONFIG.SEASON_YEAR + 1, 11, 31);
  const events = cal.getEvents(start, end);
  let deleted = 0;

  for (const ev of events) {
    if ((ev.getDescription() || "").indexOf(CONFIG.SYNC_TAG) !== -1) {
      ev.deleteEvent();
      deleted++;
    }
  }
  Logger.log("Deleted " + deleted + " synced events.");
}

// --------------- CORE LOGIC ---------------

function processSheet_(sheet) {
  const data = sheet.getDataRange().getValues();
  if (data.length < 2) return { created: 0, deleted: 0 };

  // Row 1 (index 0) contains the weekend date headers
  const headerRow = data[0];
  const weekendDates = parseWeekendHeaders_(headerRow);

  // Determine which rows to process
  const rowConfigs = getRowConfigs_(data);

  // Collect events we want to exist
  const desiredEvents = [];

  for (const rc of rowConfigs) {
    const rowIndex = rc.row - 1; // 0-based
    if (rowIndex < 0 || rowIndex >= data.length) {
      Logger.log("Skipping out-of-range row: " + rc.row);
      continue;
    }
    const rowData = data[rowIndex];

    for (let col = 1; col < rowData.length; col++) {
      const cellValue = String(rowData[col]).trim();
      if (!cellValue || cellValue === "") continue;

      const weekendDate = weekendDates[col];
      if (!weekendDate) continue;

      const title = rc.label + ": " + cellValue;
      desiredEvents.push({
        title: title,
        date: weekendDate,
        description:
          CONFIG.SYNC_TAG +
          "\nSheet: " +
          sheet.getName() +
          "\nRow: " +
          rc.label +
          "\nOriginal value: " +
          cellValue,
      });
    }
  }

  // Sync: remove stale events, create new ones
  return syncEvents_(desiredEvents, sheet.getName());
}

function syncEvents_(desiredEvents, sheetName) {
  const cal = CalendarApp.getCalendarById(CONFIG.CALENDAR_ID);
  if (!cal) throw new Error("Calendar not found: " + CONFIG.CALENDAR_ID);

  // Find existing synced events for this sheet
  const start = new Date(CONFIG.SEASON_YEAR, 0, 1);
  const end = new Date(CONFIG.SEASON_YEAR, 11, 31);
  const existingEvents = cal.getEvents(start, end);

  const syncedExisting = existingEvents.filter(function (ev) {
    const desc = ev.getDescription() || "";
    return (
      desc.indexOf(CONFIG.SYNC_TAG) !== -1 &&
      desc.indexOf("Sheet: " + sheetName) !== -1
    );
  });

  // Build a set of desired event keys for comparison
  const desiredKeys = new Set(
    desiredEvents.map(function (e) {
      return eventKey_(e.title, e.date);
    })
  );

  // Delete stale events (exist in calendar but not in spreadsheet anymore)
  let deleted = 0;
  for (const ev of syncedExisting) {
    const key = eventKey_(ev.getTitle(), ev.getAllDayStartDate() || ev.getStartTime());
    if (!desiredKeys.has(key)) {
      if (!CONFIG.DRY_RUN) ev.deleteEvent();
      Logger.log("DELETE: " + ev.getTitle() + " on " + key);
      deleted++;
    }
  }

  // Build set of existing keys to avoid duplicates
  const existingKeys = new Set(
    syncedExisting.map(function (ev) {
      return eventKey_(ev.getTitle(), ev.getAllDayStartDate() || ev.getStartTime());
    })
  );

  // Create new events
  let created = 0;
  for (const ev of desiredEvents) {
    const key = eventKey_(ev.title, ev.date);
    if (existingKeys.has(key)) continue; // already exists

    if (CONFIG.DRY_RUN) {
      Logger.log("DRY RUN — would create: " + ev.title + " on " + formatDate_(ev.date));
    } else {
      if (CONFIG.ALL_DAY_EVENTS) {
        cal
          .createAllDayEvent(ev.title, ev.date)
          .setDescription(ev.description);
      } else {
        const startTime = new Date(ev.date);
        startTime.setHours(CONFIG.DEFAULT_START_HOUR, 0, 0, 0);
        const endTime = new Date(startTime);
        endTime.setHours(startTime.getHours() + 1);
        cal
          .createEvent(ev.title, startTime, endTime)
          .setDescription(ev.description);
      }
      Logger.log("CREATE: " + ev.title + " on " + formatDate_(ev.date));
    }
    created++;
  }

  return { created: created, deleted: deleted };
}

// --------------- HEADER PARSING ---------------

/**
 * Parses weekend headers like "FEB 7-8", "FEB 28-MAR 1" into Date
 * objects (the Saturday / first day of the range).
 * Returns a sparse array indexed by column number.
 */
function parseWeekendHeaders_(headerRow) {
  const months = {
    JAN: 0, FEB: 1, MAR: 2, APR: 3, MAY: 4, JUN: 5,
    JUL: 6, AUG: 7, SEP: 8, OCT: 9, NOV: 10, DEC: 11,
  };

  const dates = [];

  for (let col = 1; col < headerRow.length; col++) {
    const raw = String(headerRow[col]).trim().toUpperCase();
    if (!raw) continue;

    // Try patterns:
    //   "FEB 7-8"       → single month, day range
    //   "FEB 28-MAR 1"  → cross-month range
    //   "FEB 7"         → single date
    let match;

    // Cross-month: "FEB 28-MAR 1"
    match = raw.match(/^([A-Z]+)\s+(\d+)\s*[-–]\s*([A-Z]+)\s+(\d+)$/);
    if (match) {
      const mon = months[match[1]];
      const day = parseInt(match[2], 10);
      if (mon !== undefined) {
        dates[col] = new Date(CONFIG.SEASON_YEAR, mon, day);
        continue;
      }
    }

    // Same-month range: "FEB 7-8"
    match = raw.match(/^([A-Z]+)\s+(\d+)\s*[-–]\s*(\d+)$/);
    if (match) {
      const mon = months[match[1]];
      const day = parseInt(match[2], 10);
      if (mon !== undefined) {
        dates[col] = new Date(CONFIG.SEASON_YEAR, mon, day);
        continue;
      }
    }

    // Single date: "FEB 7"
    match = raw.match(/^([A-Z]+)\s+(\d+)$/);
    if (match) {
      const mon = months[match[1]];
      const day = parseInt(match[2], 10);
      if (mon !== undefined) {
        dates[col] = new Date(CONFIG.SEASON_YEAR, mon, day);
        continue;
      }
    }
  }
  return dates;
}

// --------------- HELPERS ---------------

function getTargetSheets_(ss) {
  if (CONFIG.SHEET_NAMES && CONFIG.SHEET_NAMES.length > 0) {
    return CONFIG.SHEET_NAMES.map(function (name) {
      const s = ss.getSheetByName(name);
      if (!s) throw new Error("Sheet not found: " + name);
      return s;
    });
  }
  return [ss.getSheets()[0]];
}

function getRowConfigs_(data) {
  if (CONFIG.ROWS_TO_SYNC && CONFIG.ROWS_TO_SYNC.length > 0) {
    return CONFIG.ROWS_TO_SYNC;
  }
  // Auto-detect: every row that has a non-empty column A, skipping row 1
  // (headers) and row 2 if it looks like a duplicate header.
  const configs = [];
  for (let i = 1; i < data.length; i++) {
    const label = String(data[i][0]).trim();
    if (label) {
      configs.push({ row: i + 1, label: label });
    }
  }
  return configs;
}

function eventKey_(title, date) {
  return title + "|" + formatDate_(date);
}

function formatDate_(d) {
  if (!(d instanceof Date)) return String(d);
  return (
    d.getFullYear() +
    "-" +
    String(d.getMonth() + 1).padStart(2, "0") +
    "-" +
    String(d.getDate()).padStart(2, "0")
  );
}

// --------------- MENU ---------------

function onOpen() {
  SpreadsheetApp.getUi()
    .createMenu("Calendar Sync")
    .addItem("Sync to Calendar", "syncToCalendar")
    .addItem("Delete All Synced Events", "deleteAllSyncedEvents")
    .addSeparator()
    .addItem("Dry Run (preview)", "dryRun_")
    .addToUi();
}

function dryRun_() {
  const origDryRun = CONFIG.DRY_RUN;
  CONFIG.DRY_RUN = true;
  syncToCalendar();
  CONFIG.DRY_RUN = origDryRun;
  SpreadsheetApp.getUi().alert(
    "Dry run complete — check Apps Script logs (View → Logs) to see what would be created."
  );
}
