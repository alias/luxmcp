# luxmcp

MCP server for heat pumps with a **Luxtronik 2.x** controller (Alpha Innotec, Novelan, Siemens Novelan, ...),
packaged as one self-contained Java jar. It talks straight to the controller's config interface on TCP
port 8889, the same protocol [python-luxtronik](https://github.com/Bouni/python-luxtronik) and the
Home Assistant Luxtronik integrations use, and exposes the ~1900 known registers to Claude (or any MCP
client) with names, units, enum options and safe, allowlisted writes.

Developed against an Alpha Innotec Alterra SWC 102K3 (controller reports `MSW 10`, firmware V3.92.3).

## Why not go through Home Assistant?

The HA Luxtronik integration exposes a curated subset of entities, and HA's own MCP server only offers
Assist-style intents. For diagnosing a heat pump you want the raw registers with their names, units and
options. HA's recorder is still useful for trends, so an optional tool reads entity history from HA.

## Build

Requires Java 17+ and Maven.

```bash
mvn package
```

produces `target/luxmcp.jar` (shaded, no other files needed). Unit tests run against an in-process fake
controller; nothing touches real hardware during the build.

## Run

Quick connectivity check:

```bash
java -jar target/luxmcp.jar --host innotecwp --check
```

Claude Desktop (`claude_desktop_config.json`) / Claude Code (`claude mcp add ...`), stdio transport:

```json
{
  "mcpServers": {
    "heatpump": {
      "command": "java",
      "args": [
        "-jar", "C:/bob/ext_projects/luxmcp/target/luxmcp.jar",
        "--host", "innotecwp",
        "--docs", "C:/bob/ext_projects/luxmcp/docs/manuals",
        "--audit-log", "C:/bob/ext_projects/luxmcp/luxmcp-writes.log"
      ],
      "env": {
        "HASS_URL": "http://homeassistant.local:8123",
        "HASS_TOKEN": "<long-lived access token>"
      }
    }
  }
}
```

Add `"--allow-write"` to the args to enable `luxtronik_set_parameter`. Use absolute paths: Claude
Desktop does not start the server in the project directory, so the `./docs/manuals` default is not found
without `--docs`.

The Home Assistant token comes from your HA user profile: click your user name at the bottom of the
sidebar, open the *Security* tab, and under *Long-lived access tokens* choose *Create token*. It is shown
once; it carries your user's rights, so a separate non-admin HA user is the safer owner since luxmcp only
reads history. Leave the `env` block out if you do not want the history tool.

Remote / always-on (e.g. next to Home Assistant), Streamable HTTP on `/mcp` with a bearer token:

```bash
java -jar luxmcp.jar --host 192.168.10.41 --http 8765 --bind 0.0.0.0 --token <secret> --allow-write --audit-log /var/log/luxmcp-writes.log
```

All options: `java -jar luxmcp.jar --help`. Every option can also come from the environment
(`LUXMCP_HOST`, `LUXMCP_TOKEN`, `LUXMCP_ALLOW_WRITE`, `HASS_URL`, `HASS_TOKEN`, ...).

## Tools

| tool | what it does |
|---|---|
| `luxtronik_get_status` | curated overview: state, modes, temperatures, pressures, curve/DHW settings, counters |
| `luxtronik_search_registers` | search registers by name fragments (with German abbreviation help in the description) |
| `luxtronik_get_registers` | read specific registers by name or index, with raw value and options |
| `luxtronik_list_registers` | paged full dump of parameters / calculations / visibilities |
| `luxtronik_get_errors` | error memory and switch-off log |
| `luxtronik_list_writable` | the write allowlist with ranges, explanation and current values |
| `luxtronik_set_parameter` | change a setting (only with `--allow-write`) |
| `luxtronik_search_docs` | full-text search in the manufacturer manuals (only with `--docs`) |
| `luxtronik_get_doc_page` | one full manual page (only with `--docs`) |
| `luxtronik_get_history` | HA recorder time series (only with `--ha-url` and `--ha-token`) |

## Manuals

Put the manufacturer PDFs (Luxtronik 2.1 Betriebsanleitung Teil 1 and Teil 2, the heat pump's
Betriebsanleitung) into a folder and pass `--docs <dir>`; `./docs/manuals` is picked up automatically
and is git-ignored because the manuals are copyrighted. At startup the text is extracted with PDFBox,
split into page-sized passages labelled with the manual's own table-of-contents headings, and indexed
in memory. The error table ("Fehlerdiagnose / Fehlermeldungen") is parsed too: `luxtronik_get_errors`
appends cause and remedy for every code in the error memory. Plain `.txt`/`.md` files in the folder
are indexed as well, so your own notes can live there.

Independent of the manuals, `src/main/resources/luxtronik/register_notes.tsv` carries curated
one-line explanations (menu path, meaning, caveats) for the ~130 registers that matter most. They are
merged into the register descriptions and therefore show up in `luxtronik_search_registers` and
`luxtronik_get_registers`. Extend the file when you learn what a register means.

Reads are cached for `--cache-seconds` (default 10) and serialized with a lock, because the controller is
a slow embedded device that becomes unstable under concurrent access.

## Writing: safety model

Writing to the CFI is an undocumented API and the controller stores settings in NAND flash with limited
erase cycles. Therefore:

1. Writes are off unless `--allow-write` is given; without it the tool is not even registered.
2. Only allowlisted parameters can be written. The built-in list covers everyday settings: operating
   modes for heating / hot water / cooling, "Temperatur +/-", hot water target and hysteresis, the
   heating and mixing-circuit-1 curves, cooling release, electric DHW reheating. See
   `WritePolicy.DEFAULT_RULES` or call `luxtronik_list_writable`.
3. A parameter must additionally be marked writeable in python-luxtronik's definitions.
4. Numeric values are range-checked in user units (e.g. hot water 30..60 °C).
5. Writes are rate limited (5 s apart), skipped when the value is already set, verified by reading the
   controller back, and logged (stderr and optionally `--audit-log`).

Your own allowlist: `--allowlist my.txt` with lines `NAME [min max] [# note]` replaces the built-in one.

## Register table

`src/main/resources/luxtronik/registers.json` is generated from python-luxtronik and contains all
register definitions plus the datatype semantics (scaling factors, enum codes, ...). To pick up upstream
additions:

```bash
pip install -U luxtronik            # or a git checkout: pip install -e path/to/python-luxtronik
python tools/gen_registers.py
mvn package
```

## End-to-end test against the real controller

```bash
python tools/stdio_smoke.py --host innotecwp
```

starts the jar over stdio, performs the MCP handshake and calls every read-only tool. With
`--allow-write` it additionally shows two writes being *refused* by the policy; it never writes.
