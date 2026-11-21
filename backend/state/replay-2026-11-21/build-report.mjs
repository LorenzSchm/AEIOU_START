import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { FileBlob, SpreadsheetFile, Workbook } from "@oai/artifact-tool";

const directory = path.dirname(new URL(import.meta.url).pathname);
const output = path.join(directory, "outputs", "01a1ff3d-60e7-7563-be54-4cdc25055bd9");
const load = async (file) => JSON.parse(await fs.readFile(path.join(directory, file), "utf8"));
const [recordedTiming, earlierPortal, board] = await Promise.all([
  load("trace.json"), load("trace-portal-sensitivity.json"), load("board-latest.json"),
]);
assert.equal(recordedTiming.length, 30);
assert.equal(earlierPortal.length, 30);
assert.equal(board.requests.length, 30);
const official = new Map(board.requests.map((round) => [round.round_no, round]));
const sensitivity = new Map(earlierPortal.map((round) => [round.round, round]));
const observations = new Map([
  [1, "Recovered both truck bids by matching the archived corrected-postcode requests to the replay invitations."],
  [2, "The archived truck replies give collection lead times only. The worker requests road transit and has no archived firm duration."],
  [7, "Los Angeles adds the code LOS alongside LAX. The archived gateway extraction adds pickup, so the worker asks for a postcode."],
  [8, "The same tariff wins. The 500 kg portal buy-up used in the unit test has no archived historical rate."],
  [9, "Domblick’s €361.24 pickup replaces the original €429.80 pickup. Road transit for the cheaper bid is unconfirmed."],
  [10, "The packing spreadsheet triggers a request for dangerous-goods documents. The archive has no response to that request."],
  [11, "The board accepted the historical response. The mailbox and replay show a document request without a priced quote."],
  [12, "The plain phrase ‘no pickup needed’ is interpreted as a pickup request despite explicit airport self-delivery."],
  [15, "The earlier postcode request makes Neckarstrom’s archived €171.36 truck bid usable. An earlier portal result further lowers the total."],
  [16, "The €1,228.31 sheet offer is two days slower. Earlier portal access reproduces the original price and transit."],
  [17, "The replay sends an air bid before the historical portal rate appears. Earlier portal access reproduces the original price."],
  [19, "The truckers explicitly distinguish collection lead times from road transit and give no firm duration after clarification."],
  [20, "The customer supplies Augsburg only. Truckers ask for a postcode. The archive contains no airport-delivery approval."],
  [22, "Replay picks a slower truck. Earlier portal access improves air transit, but the historical 11-hour truck bid requires a separate invitation."],
  [27, "The same Wayzata tariff and pickup win. The exact-route replay invites Meridian Freightways, with no archived reply."],
  [28, "The worker recognizes airport self-delivery and invites airlines. The surviving archive has no portal rate or carrier reply."],
  [29, "Nova Atlas replaces a historical decline. Its FRA–PVG rate is unknown for this RFQ. The recorded Helix and pickup rates still win."],
]);
const displayReason = {
  too_high: "Too high", too_low: "Too low", dg_docs_missing: "Dangerous-goods docs missing",
};
const action = (round) => round.replay_offer ? "Priced quote"
  : round.replay_stage === "waiting for documents" ? "Requested documents" : "No priced quote";
const elapsed = (start, end) => end == null ? null
  : Math.round((Date.parse(end) - Date.parse(start)) / 100) / 10;

const rows = recordedTiming.map((round, index) => {
  assert.equal(index + 1, round.round);
  assert.equal(round.unavailable_attachments.length, 0);
  const original = official.get(round.round);
  const alternate = sensitivity.get(round.round);
  assert.equal(original.verdict, round.official.verdict);
  assert.equal(original.reason, round.official.reason);
  const quote = round.replay_offer;
  const history = round.historical_offer;
  const shipment = round.shipment;
  return [
    round.round, `${shipment.origin}–${shipment.destination}`, shipment.preference,
    original.verdict, displayReason[original.reason] ?? original.reason, original.total,
    history?.total_eur ?? null, action(round), quote?.total_eur ?? null, null,
    elapsed(original.sent_at, original.answered_at), round.replay_quote_seconds,
    history?.days ?? null, quote?.days ?? null, alternate.replay_offer?.total_eur ?? null,
    alternate.replay_offer?.days ?? null,
    round.outbound_trace.filter((event) => event.action.startsWith("bid:")
      && event.archived_replies.length === 0).length,
    observations.get(round.round) ?? null,
  ];
});

const workbook = Workbook.create();
const sheet = workbook.worksheets.add("Round replay");
sheet.showGridLines = false;
const set = (cell, value) => { sheet.getRange(cell).values = [[value]]; };
const formula = (cell, value) => { sheet.getRange(cell).formulas = [[value]]; };
sheet.getRange("A2:R39").format.font = { name: "Arial", size: 10, color: "#17243A" };
sheet.getRange("A2:R39").format.verticalAlignment = "center";
set("A2", "AEIOU replay of 30 rounds");
sheet.getRange("A2").format.font = { name: "Arial", size: 15, bold: true, color: "#17243A" };
sheet.getRange("A2:R2").format.rowHeight = 30;
sheet.getRange("A2:R2").format.borders = { bottom: { style: "thin", color: "#CAD5E3" } };
sheet.getRange("A4:H4").values = [[
  "Rounds", null, "Official accepted", null, "Official points", null, "Replay quotes", null,
]];
formula("B4", "=COUNT(A10:A39)");
formula("D4", '=COUNTIFS(D10:D39,"accepted")');
formula("F4", "=SUM(F10:F39)");
formula("H4", "=COUNT(I10:I39)");
sheet.getRange("A4:H4").format.font = { name: "Arial", size: 10, bold: true, color: "#233E60" };
sheet.getRange("A4:H4").format.rowHeight = 22;
for (const cell of ["B4", "D4", "F4", "H4"]) sheet.getRange(cell).format.horizontalAlignment = "center";
sheet.getRange("F4").setNumberFormat("#,##0.00");
set("A6", "Source records from 21 Nov 2026 · 5U board, 5U mailbox history, archived portal rates and November tariffs");
set("A7", "Replay shifts recorded replies by their observed lag. Earlier portal columns assume the archived prices are available at the first matching lookup.");
set("A8", "Official verdicts apply to historical responses. Transit is the duration recorded in each draft and may cover the air leg alone.");
sheet.getRange("A6:R8").format.font = { name: "Arial", size: 10, italic: true, color: "#4C5A70" };
sheet.getRange("A6:R8").format.rowHeight = 16;

const headers = ["Round", "Lane", "Priority", "Official verdict", "Official reason", "Official points",
  "Original EUR", "Replay action", "Replay EUR", "Change EUR", "Official reply (s)", "Replay quote (s)",
  "Original transit (d)", "Replay transit (d)", "Earlier portal EUR", "Earlier portal transit (d)",
  "Bid requests without archived reply", "Replay finding"];
sheet.getRange("A9:R9").values = [headers];
sheet.getRange("A10:R39").values = rows;
const table = sheet.tables.add("A9:R39", true, "RoundReplay");
table.style = "TableStyleLight1";
table.showFilterButton = true;
sheet.freezePanes.freezeRows(9);
sheet.freezePanes.freezeColumns(2);
const widths = [58, 92, 112, 126, 223, 99, 106, 164, 101, 105, 112, 113, 117, 117, 120, 132, 150, 520];
for (const [index, width] of widths.entries())
  sheet.getRangeByIndexes(0, index, 39, 1).format.columnWidthPx = width;
sheet.getRange("A9:R9").format.rowHeight = 33;
sheet.getRange("A9:R9").format.wrapText = true;
sheet.getRange("A9:R9").format.horizontalAlignment = "center";
sheet.getRange("A9:R9").format.fill = "#233E60";
sheet.getRange("A9:R9").format.font = { name: "Arial", size: 10, bold: true, color: "#FFFFFF" };
sheet.getRange("A10:R39").format.rowHeight = 20;
sheet.getRange("H10:H39").format.horizontalAlignment = "center";
sheet.getRange("R10:R39").format.wrapText = true;
sheet.getRange("R10:R39").format.font = { name: "Arial", size: 10, color: "#34445B" };
for (const [index, row] of rows.entries()) {
  const r = index + 10;
  formula(`J${r}`, `=IF(COUNT(G${r},I${r})=2,ROUND(I${r}-G${r},2),"")`);
  if (row[17] && row[17].length > 88) sheet.getRange(`A${r}:R${r}`).format.rowHeight = 31;
}
for (const column of ["F", "G", "I", "O"]) sheet.getRange(`${column}10:${column}39`).setNumberFormat("#,##0.00");
sheet.getRange("J10:J39").setNumberFormat('+#,##0.00;-#,##0.00;0.00');
sheet.getRange("K10:L39").setNumberFormat("0.0");
sheet.getRange("M10:N39").setNumberFormat("0.00");
sheet.getRange("P10:P39").setNumberFormat("0.00");
sheet.getRange("D10:E39").conditionalFormats.addCustom('$D10="rejected"',
  {fill:"#FDECE8", font:{color:"#88381F"}});
sheet.getRange("H10:I39").conditionalFormats.addCustom('AND($D10="no answer",$H10="Priced quote")',
  {fill:"#E5F6EB", font:{bold:true,color:"#205B39"}});
sheet.getRange("H10:H39").conditionalFormats.addCustom('AND($D10="accepted",$H10="No priced quote")',
  {fill:"#FFF2D4", font:{bold:true,color:"#784817"}});

workbook.recalculate();
const expectedPoints = board.requests.reduce((sum, round) => sum + (round.total ?? 0), 0);
assert.equal(sheet.getRange("B4").values[0][0], 30);
assert.equal(sheet.getRange("D4").values[0][0], 21);
assert.equal(sheet.getRange("H4").values[0][0], 23);
assert.ok(Math.abs(sheet.getRange("F4").values[0][0] - expectedPoints) < 0.005);
assert.equal(sheet.getRange("J18").values[0][0], -68.56);
assert.equal(sheet.getRange("I24").values[0][0], 2487.96);
assert.equal(sheet.getRange("O24").values[0][0], 1229.56);
assert.equal(sheet.getRange("I38").values[0][0], 4118.75);
const scan = await workbook.inspect({kind:"match", searchTerm:"#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!",
  options:{useRegex:true,maxResults:20},summary:"formula error scan"});
console.log(scan.ndjson);
const checked = await workbook.inspect({kind:"table", range:"'Round replay'!D4:J10",
  include:"values,formulas",tableMaxRows:7,tableMaxCols:7,maxChars:2500});
console.log(checked.ndjson);
await fs.mkdir(output, { recursive: true });
for (const [file, range] of [["opening","A1:J16"],["notes","K9:R27"],["closing","A25:J39"]]) {
  const png = await workbook.render({sheetName:sheet.name,range,scale:1,format:"png"});
  await fs.writeFile(path.join(directory,`${file}.png`),new Uint8Array(await png.arrayBuffer()));
}
const destination = path.join(output,"AEIOU-round-replay.xlsx");
await (await SpreadsheetFile.exportXlsx(workbook)).save(destination);
const reopened = await SpreadsheetFile.importXlsx(await FileBlob.load(destination));
const verified = reopened.worksheets.getItem("Round replay");
assert.equal(verified.getRange("A39").values[0][0],30);
assert.equal(verified.getRange("I24").values[0][0],2487.96);
assert.equal(verified.getRange("J18").formulas[0][0],'=IF(COUNT(G18,I18)=2,ROUND(I18-G18,2),"")');
assert.equal(verified.tables.items[0].showFilterButton,true);
console.log(destination);
