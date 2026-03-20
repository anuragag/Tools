import { google } from "googleapis";
import { readFile } from "fs/promises";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const CONFIG_PATH = resolve(__dirname, "..", "config.json");

export async function loadConfig() {
  const raw = await readFile(CONFIG_PATH, "utf-8");
  return JSON.parse(raw);
}

// ---- Header parsing (ported from Apps Script) ----

const MONTHS = {
  JAN: 0, FEB: 1, MAR: 2, APR: 3, MAY: 4, JUN: 5,
  JUL: 6, AUG: 7, SEP: 8, OCT: 9, NOV: 10, DEC: 11,
};

function parseWeekendHeaders(headerRow, seasonYear) {
  const dates = [];
  for (let col = 1; col < headerRow.length; col++) {
    const raw = String(headerRow[col] || "").trim().toUpperCase();
    if (!raw) continue;

    let match;

    // Cross-month: "FEB 28-MAR 1"
    match = raw.match(/^([A-Z]+)\s+(\d+)\s*[-–]\s*([A-Z]+)\s+(\d+)$/);
    if (match && MONTHS[match[1]] !== undefined) {
      dates[col] = new Date(seasonYear, MONTHS[match[1]], parseInt(match[2], 10));
      continue;
    }

    // Same-month range: "FEB 7-8"
    match = raw.match(/^([A-Z]+)\s+(\d+)\s*[-–]\s*(\d+)$/);
    if (match && MONTHS[match[1]] !== undefined) {
      dates[col] = new Date(seasonYear, MONTHS[match[1]], parseInt(match[2], 10));
      continue;
    }

    // Single date: "FEB 7"
    match = raw.match(/^([A-Z]+)\s+(\d+)$/);
    if (match && MONTHS[match[1]] !== undefined) {
      dates[col] = new Date(seasonYear, MONTHS[match[1]], parseInt(match[2], 10));
      continue;
    }
  }
  return dates;
}

function formatDate(d) {
  return (
    d.getFullYear() +
    "-" +
    String(d.getMonth() + 1).padStart(2, "0") +
    "-" +
    String(d.getDate()).padStart(2, "0")
  );
}

function eventKey(title, dateStr) {
  return title + "|" + dateStr;
}

// ---- Spreadsheet reading ----

async function readSheetData(auth, config) {
  const sheets = google.sheets({ version: "v4", auth });

  const sheetNames = config.sheetNames?.length
    ? config.sheetNames
    : [null]; // null = first sheet

  const allSheetData = [];

  for (const name of sheetNames) {
    const range = name ? `'${name}'` : "Sheet1";
    const res = await sheets.spreadsheets.values.get({
      spreadsheetId: config.spreadsheetId,
      range,
    });
    allSheetData.push({
      name: name || "Sheet1",
      values: res.data.values || [],
    });
  }

  return allSheetData;
}

function getRowConfigs(data, config) {
  if (config.rowsToSync?.length) return config.rowsToSync;

  // Auto-detect: every row with a non-empty column A (skip header)
  const configs = [];
  for (let i = 1; i < data.length; i++) {
    const label = String(data[i][0] || "").trim();
    if (label) {
      configs.push({ row: i + 1, label });
    }
  }
  return configs;
}

function collectDesiredEvents(data, config, sheetName) {
  if (data.length < 2) return [];

  const weekendDates = parseWeekendHeaders(data[0], config.seasonYear);
  const rowConfigs = getRowConfigs(data, config);
  const events = [];

  for (const rc of rowConfigs) {
    const rowIndex = rc.row - 1;
    if (rowIndex < 0 || rowIndex >= data.length) continue;
    const rowData = data[rowIndex];

    for (let col = 1; col < rowData.length; col++) {
      const cellValue = String(rowData[col] || "").trim();
      if (!cellValue) continue;

      const weekendDate = weekendDates[col];
      if (!weekendDate) continue;

      const title = rc.label + ": " + cellValue;
      events.push({
        title,
        date: weekendDate,
        dateStr: formatDate(weekendDate),
        description:
          config.syncTag +
          "\nSheet: " + sheetName +
          "\nRow: " + rc.label +
          "\nOriginal value: " + cellValue,
      });
    }
  }

  return events;
}

// ---- Calendar sync ----

async function getExistingSyncedEvents(calendar, calendarId, config, sheetName) {
  const timeMin = new Date(config.seasonYear, 0, 1).toISOString();
  const timeMax = new Date(config.seasonYear, 11, 31).toISOString();

  const events = [];
  let pageToken;

  do {
    const res = await calendar.events.list({
      calendarId,
      timeMin,
      timeMax,
      maxResults: 2500,
      singleEvents: true,
      pageToken,
    });
    events.push(...(res.data.items || []));
    pageToken = res.data.nextPageToken;
  } while (pageToken);

  return events.filter((ev) => {
    const desc = ev.description || "";
    return (
      desc.includes(config.syncTag) &&
      desc.includes("Sheet: " + sheetName)
    );
  });
}

function existingEventKey(ev) {
  const dateStr = ev.start?.date || ev.start?.dateTime?.slice(0, 10);
  return ev.summary + "|" + dateStr;
}

async function syncEventsForSheet(auth, config, sheetName, desiredEvents) {
  const calendar = google.calendar({ version: "v3", auth });
  const calendarId = config.calendarId;

  const existing = await getExistingSyncedEvents(calendar, calendarId, config, sheetName);

  const desiredKeys = new Set(desiredEvents.map((e) => eventKey(e.title, e.dateStr)));
  const existingKeys = new Set(existing.map(existingEventKey));

  let deleted = 0;
  let created = 0;

  // Delete stale events
  for (const ev of existing) {
    const key = existingEventKey(ev);
    if (!desiredKeys.has(key)) {
      if (!config.dryRun) {
        await calendar.events.delete({ calendarId, eventId: ev.id });
      }
      console.log("DELETE:", ev.summary, "on", key.split("|")[1]);
      deleted++;
    }
  }

  // Create new events
  for (const ev of desiredEvents) {
    const key = eventKey(ev.title, ev.dateStr);
    if (existingKeys.has(key)) continue;

    if (config.dryRun) {
      console.log("DRY RUN — would create:", ev.title, "on", ev.dateStr);
    } else {
      const eventBody = {
        summary: ev.title,
        description: ev.description,
      };

      if (config.allDayEvents) {
        eventBody.start = { date: ev.dateStr };
        eventBody.end = { date: ev.dateStr };
      } else {
        const startTime = new Date(ev.date);
        startTime.setHours(config.defaultStartHour, 0, 0, 0);
        const endTime = new Date(startTime);
        endTime.setHours(startTime.getHours() + 1);
        eventBody.start = { dateTime: startTime.toISOString() };
        eventBody.end = { dateTime: endTime.toISOString() };
      }

      await calendar.events.insert({ calendarId, requestBody: eventBody });
      console.log("CREATE:", ev.title, "on", ev.dateStr);
    }
    created++;
  }

  return { created, deleted };
}

// ---- Public API ----

export async function syncToCalendar(auth, configOverrides = {}) {
  const config = { ...(await loadConfig()), ...configOverrides };

  const allSheets = await readSheetData(auth, config);
  let totalCreated = 0;
  let totalDeleted = 0;

  for (const { name, values } of allSheets) {
    console.log("Processing sheet:", name);
    const desired = collectDesiredEvents(values, config, name);
    const result = await syncEventsForSheet(auth, config, name, desired);
    totalCreated += result.created;
    totalDeleted += result.deleted;
  }

  console.log(`\nSync complete. Created: ${totalCreated}, Deleted (stale): ${totalDeleted}`);
  if (config.dryRun) console.log("(DRY RUN — no events were actually modified)");
}

export async function deleteAllSyncedEvents(auth, configOverrides = {}) {
  const config = { ...(await loadConfig()), ...configOverrides };
  const calendar = google.calendar({ version: "v3", auth });
  const calendarId = config.calendarId;

  const timeMin = new Date(config.seasonYear - 1, 0, 1).toISOString();
  const timeMax = new Date(config.seasonYear + 1, 11, 31).toISOString();

  const events = [];
  let pageToken;

  do {
    const res = await calendar.events.list({
      calendarId,
      timeMin,
      timeMax,
      maxResults: 2500,
      singleEvents: true,
      pageToken,
    });
    events.push(...(res.data.items || []));
    pageToken = res.data.nextPageToken;
  } while (pageToken);

  let deleted = 0;
  for (const ev of events) {
    if ((ev.description || "").includes(config.syncTag)) {
      if (!config.dryRun) {
        await calendar.events.delete({ calendarId, eventId: ev.id });
      }
      deleted++;
    }
  }
  console.log(`Deleted ${deleted} synced events.`);
}
