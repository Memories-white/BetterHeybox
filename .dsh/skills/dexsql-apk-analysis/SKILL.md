---
name: dexsql-apk-analysis
description: >-
  Analyze Android APK/DEX archives with DexSQL (LSPosed) — read-only SQL over
  classes, methods, fields, strings, call edges and field accesses, plus paged
  smali decode and Manifest/resource inspection. Use whenever a task involves
  inspecting an APK in this repo: locating hook targets, tracing call graphs,
  finding API endpoints or constants, checking a class or method exists in a
  given version, or comparing two app versions, without decompiling to a source
  tree first.
whenToUse: >-
  Any question of the form "does APK X contain / call / reference Y?", "which
  class handles screen Z?", "find the endpoint / flag / string used by method
  M", "what does method M actually do?", or version-to-version structural
  diffs. Prefer this over apktool/jadx whenever the answer is metadata,
  structure, callers/callees, constants, or one method's smali.
---

# DexSQL APK analysis (workspace default)

DexSQL is installed and is the **first tool to try** for any APK/DEX question in
this workspace. It answers structural questions in well under a second on the
114 MB Heybox APKs, without a JVM, decompile step, or temp output tree.

## Where it is

| Item | Location |
| --- | --- |
| Binary | `E:\Tools\DexSQL\v0.1.0\dexsql.exe` (v0.1.0) — **must stay off the C: drive**, E: is the accepted location |
| PATH shim | `E:\Tools\DexSQL\bin\dexsql.cmd` → `E:\Tools\DexSQL\bin` on user PATH (new shells only) |
| DSH MCP server | server name `dexsql`, command `dexsql.exe mcp`; tools appear as `mcp__dexsql__*` |
| Licenses | `dexsql extract licenses --output <dir>` |
| Cache file | `cache.dxc2` produced by `prepare-cache`, consumed by `query-cache` |

If `dexsql` is not on PATH in the current shell, call the absolute exe path.

## Choose the right tool

- **dexsql** → class/method/field/string inventory, existence checks, callers and
  callees, field access sites, try/catch layout, annotations, debug line numbers,
  string constants, manifest, resources.arsc, one method's smali, version diffs.
- **apktool / jadx** → only when you need a browsable decompiled Java tree, full
  resource XML for a whole screen, or long multi-method source reading. Keep such
  output out of git (use `analysis/` or `_apk_analysis/` scratch space).
- **Frida / runtime** → behavior that static data cannot prove (dynamic dispatch,
  reflection, network at runtime).

Never claim an APK fact from an old decompile tree when the version under
discussion is newer — re-verify with one dexsql query instead.

## Schema essentials (verified against heybox 1.3.39x)

Tables are per-DEX, joined through a single `--input` dataset. Key tables:

```
classes(descriptor, superclass_descriptor, interface_count, source_file, access_flags, class_idx, file_id)
methods(class_descriptor, name, descriptor, return_descriptor, param_descriptors,
        param_count, access_flags, opcode_count, method_idx, file_id)
fields(class_descriptor, name, descriptor, type_descriptor, field_idx)
strings(string_pool_id, string_idx, value)
method_calls(method, method_class, method_name, callee, callee_class, callee_name)
method_field_accesses(method, field, field_class, field_name, field_type)
method_strings(method, method_class, method_name, value)
method_used_types(method, type_descriptor)
try_items / catch_handlers / code_items   -- control flow layout per method
debug_positions(method_idx, line, address, ...) -- source line numbers
class_methods / class_fields               -- per-class member views
```

Notes that save time:

- `classes` has `descriptor` (not `class_descriptor`); `methods`/`fields` have
  `class_descriptor`.
- `Utf8View` columns (method_calls, method_field_accesses, method_strings) work
  with `LIKE` and `=`, but cannot be passed to functions expecting `Utf8`.
- Descriptors are exact DEX form: `Lcom/max/xiaoheihe/MainActivity;`, method
  `Lcom/max/xiaoheihe/MainActivity;->onCreate(Landroid/os/Bundle;)V`.
- Always spell `--input` with an explicit path; the CLI resolves relative paths
  against the current working directory.

## Recipes

Discover before filtering — exact-guess queries on this app are usually wrong
(the real package is `com.max.xiaoheihe`, not `com.xiaheihe`):

```powershell
$exe = 'E:\Tools\DexSQL\v0.1.0\dexsql.exe'
$apk = "E:\Project\BetterHeybox\analysis\heybox_now.apk"

# 1. What classes match? (cheap discovery)
& $exe query -i $apk -s "SELECT descriptor FROM classes WHERE descriptor LIKE '%MainActivity%' LIMIT 10"

# 2. Methods of one class
& $exe query -i $apk -s "SELECT name, descriptor, param_descriptors FROM methods WHERE class_descriptor = 'Lcom/max/xiaoheihe/MainActivity;' LIMIT 20"

# 3. Who calls what / who calls target
& $exe query -i $apk -s "SELECT method, callee FROM method_calls WHERE method LIKE '%MainActivity%onCreate%' LIMIT 10"
& $exe query -i $apk -s "SELECT DISTINCT method FROM method_calls WHERE callee LIKE '%xiaoheihe/utils/h;->Q0%'"

# 4. String constants and which method uses them
& $exe query -i $apk -s "SELECT string_idx, value FROM strings WHERE value LIKE '%api.xiaoheihe.cn%' LIMIT 5"
& $exe query -i $apk -s "SELECT method FROM method_strings WHERE value = 'https://api.xiaoheihe.cn/' LIMIT 20"

# 5. Field access sites
& $exe query -i $apk -s "SELECT method, field FROM method_field_accesses WHERE field_name LIKE '%token%' LIMIT 20"

# 6. Inventory counts / version diff input
& $exe query -i $apk -s "SELECT count(*) AS classes FROM classes" -s "SELECT count(*) AS methods FROM methods"

# 7. Smali for one method (paged by bytes; --class for all defined methods)
& $exe decode -i $apk --method 'Lcom/max/xiaoheihe/MainActivity;->onCreate(Landroid/os/Bundle;)V'
& $exe decode -i $apk --file-id 0 --method-idx 12345
```

Multiple inputs share one dataset for cross-DEX review:

```powershell
& $exe query -i old.apk -i new.apk -s "SELECT file_id, count(*) FROM classes GROUP BY file_id"
```

Persistent cache for repeated analysis of the same 114 MB APK:

```powershell
& $exe prepare-cache -i $apk --output analysis\cache\heybox_now
& $exe query-cache --cache-file analysis\cache\heybox_now\cache.dxc2 -s "SELECT count(*) FROM classes"
```

## Manifest and resources (MCP only)

The CLI does not expose Manifest/resources; use the MCP tools when the DSH
session has them, or the same binary's server manually:

- `mcp__dexsql__open_dataset` (`inputs` = APK paths) → `dataset_id`
- `mcp__dexsql__get_manifest` (`mode: summary` for components/permissions,
  `mode: xml` for decoded XML)
- `mcp__dexsql__get_manifest_components` (`kind`, `only_exported`)
- `mcp__dexsql__list_resource_files` / `read_resource` / `query_resources`
- `mcp__dexsql__query` / `decode` — same SQL/smali semantics as CLI, paged via
  `next_offset`; SQL is re-executed per page, so always use a stable `ORDER BY`
- `mcp__dexsql__close_dataset` when done; up to 4 datasets, 1 GiB inputs each.

If MCP tools are not loaded in the current session, run the CLI equivalents and
report that the session needs a refresh for the MCP path.

## Rules of engagement

1. Query before reading a decompile tree; state the APK path and version you queried.
2. For every fact in a report, include the query that produced it and the exact
   descriptor; keep `--limit`/`next_offset` paging in mind for large results.
3. Do not dump whole APKs to smali directories; `decode` on demand only.
4. Results depend on the input: no `--trusted-input` unless the APK came from a
   controlled build pipeline.
5. Add findings to the relevant `analysis\*.md` report instead of creating
   one-off scratch notes at repo root.
