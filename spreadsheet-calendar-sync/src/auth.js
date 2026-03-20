import { google } from "googleapis";
import { createServer } from "http";
import { URL } from "url";
import { readFile, writeFile } from "fs/promises";
import { existsSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = resolve(__dirname, "..");
const TOKEN_PATH = resolve(PROJECT_ROOT, "token.json");
const CREDENTIALS_PATH = resolve(PROJECT_ROOT, "credentials.json");

const SCOPES = [
  "https://www.googleapis.com/auth/spreadsheets.readonly",
  "https://www.googleapis.com/auth/calendar",
];

async function loadCredentials() {
  if (!existsSync(CREDENTIALS_PATH)) {
    throw new Error(
      `Missing ${CREDENTIALS_PATH}\n` +
        "Download OAuth2 credentials from Google Cloud Console:\n" +
        "  1. Go to https://console.cloud.google.com/apis/credentials\n" +
        "  2. Create an OAuth 2.0 Client ID (type: Desktop app)\n" +
        "  3. Download the JSON and save it as credentials.json in the project root"
    );
  }
  const content = await readFile(CREDENTIALS_PATH, "utf-8");
  const keys = JSON.parse(content);
  const { client_id, client_secret, redirect_uris } =
    keys.installed || keys.web;
  return new google.auth.OAuth2(client_id, client_secret, redirect_uris[0]);
}

async function loadSavedToken(oauth2Client) {
  if (!existsSync(TOKEN_PATH)) return false;
  const token = JSON.parse(await readFile(TOKEN_PATH, "utf-8"));
  oauth2Client.setCredentials(token);
  return true;
}

async function saveToken(oauth2Client) {
  await writeFile(TOKEN_PATH, JSON.stringify(oauth2Client.credentials));
}

/**
 * Opens a local HTTP server to receive the OAuth callback.
 * Prints the auth URL for the user to visit.
 */
async function authorizeInteractively(oauth2Client) {
  const PORT = 3000;
  const REDIRECT = `http://localhost:${PORT}`;

  // Override redirect URI for local server
  oauth2Client._redirectUri = REDIRECT;

  const authUrl = oauth2Client.generateAuthUrl({
    access_type: "offline",
    scope: SCOPES,
    redirect_uri: REDIRECT,
  });

  console.log("\nOpen this URL in your browser to authorize:\n");
  console.log(authUrl);
  console.log("\nWaiting for authorization...");

  const code = await new Promise((resolve, reject) => {
    const server = createServer((req, res) => {
      const url = new URL(req.url, `http://localhost:${PORT}`);
      const code = url.searchParams.get("code");
      if (code) {
        res.writeHead(200, { "Content-Type": "text/html" });
        res.end("<h1>Authorization successful!</h1><p>You can close this tab.</p>");
        server.close();
        resolve(code);
      } else {
        res.writeHead(400);
        res.end("Missing code parameter");
      }
    });
    server.listen(PORT, () => {});
    server.on("error", reject);
  });

  const { tokens } = await oauth2Client.getToken({ code, redirect_uri: REDIRECT });
  oauth2Client.setCredentials(tokens);
  await saveToken(oauth2Client);
  console.log("Authorization saved to token.json");
}

/**
 * Returns an authenticated OAuth2 client.
 * If interactive=true, will prompt for browser auth if no token exists.
 * If interactive=false (for scheduled runs), fails if no token.
 */
export async function getAuthClient({ interactive = false } = {}) {
  const oauth2Client = await loadCredentials();

  const hasToken = await loadSavedToken(oauth2Client);
  if (hasToken) {
    // Refresh if expired
    oauth2Client.on("tokens", async (tokens) => {
      if (tokens.refresh_token) {
        oauth2Client.setCredentials(tokens);
        await saveToken(oauth2Client);
      }
    });
    return oauth2Client;
  }

  if (!interactive) {
    throw new Error(
      "No saved token. Run `npm run auth` first to authorize interactively."
    );
  }

  await authorizeInteractively(oauth2Client);
  return oauth2Client;
}
