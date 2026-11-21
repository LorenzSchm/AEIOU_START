// Downloads the live backend spec into openapi.json (used by `npm run gen:api:live`).
import { writeFile } from "node:fs/promises";

const url = process.env.OPENAPI_URL ?? "http://localhost:8080/v3/api-docs";
const res = await fetch(url);
if (!res.ok) {
  console.error(`Could not fetch ${url}: ${res.status} ${res.statusText}`);
  process.exit(1);
}
const spec = await res.json();
await writeFile(new URL("../openapi.json", import.meta.url), JSON.stringify(spec, null, 2) + "\n");
console.log(`openapi.json updated from ${url}`);
