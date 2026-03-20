#!/usr/bin/env node

import { getAuthClient } from "./auth.js";
import { syncToCalendar, deleteAllSyncedEvents } from "./sync.js";

const command = process.argv[2];
const flags = process.argv.slice(3);
const dryRun = flags.includes("--dry-run");

async function main() {
  switch (command) {
    case "auth": {
      await getAuthClient({ interactive: true });
      console.log("Authentication successful.");
      break;
    }

    case "sync": {
      const auth = await getAuthClient({ interactive: false });
      await syncToCalendar(auth, { dryRun });
      break;
    }

    case "delete": {
      const auth = await getAuthClient({ interactive: false });
      await deleteAllSyncedEvents(auth);
      break;
    }

    default:
      console.log(`Usage:
  node src/index.js auth       Authorize with Google (first-time setup)
  node src/index.js sync       Sync spreadsheet to calendar
  node src/index.js sync --dry-run   Preview without making changes
  node src/index.js delete     Delete all synced events`);
      process.exit(1);
  }
}

main().catch((err) => {
  console.error("Error:", err.message);
  process.exit(1);
});
