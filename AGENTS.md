# AGENTS.md

面向在本仓库中工作的 AI 代理（DSH / Claude Code / 其他）的协作约定。

## 项目速览

- **BetterHeybox**：增强小黑盒（Heybox）的 LSPosed 模块，GPLv3。宿主包名 `com.max.xiaoheihe`。
- **入口**：`app/src/main/resources/META-INF/xposed/`（`java_init.list` 入口类、`scope.list` 作用域、`module.prop`）。
- **Hook 代码**：`app/src/main/java/com/better/heybox/hooks/`；工具类在 `.../util/`。
- **配置存储**：宿主的 `/data/data/com.max.xiaoheihe/shared_prefs/betterheybox.xml`（面板入口在宿主的 设置→通用设置）。
- **兼容版本**：最低小黑盒 **1.3.396**，主要针对 **1.3.397**（`analysis/Release-1.3.397.1138.-20261014.apk`），更低版本不处理。
- 环境：Gradle 9.7.1、SDK Platform 37、JDK 17+。编译：`gradlew.bat assembleDebug`，产物 `app/build/outputs/apk/debug/app-debug.apk`。
- 日志：`adb logcat -s BetterHeybox`；模块内「记录日志」开关可写入文件。

## APK 分析：默认用 DexSQL

分析宿主 APK（结构、类/方法、字符串、调用关系、Manifest、资源）时，**先查 DexSQL**，
不要一上来就 apktool/jadx 全量反编译。完整用法见
[`.dsh/skills/dexsql-apk-analysis/SKILL.md`](.dsh/skills/dexsql-apk-analysis/SKILL.md)；要点：

- 可执行文件：`E:\Tools\DexSQL\v0.1.0\dexsql.exe`，v0.1.0（**装在 E 盘，禁止装到 C 盘**）；
  新开终端可直接用 PATH 里的 `dexsql`（`E:\Tools\DexSQL\bin\dexsql.cmd`）。
- DSH 中同时注册为 MCP server `dexsql`（工具名 `mcp__dexsql__*`），覆盖 CLI 没有的
  Manifest / resources.arsc 查询：`open_dataset` → `get_manifest` /
  `get_manifest_components` / `query_resources` / `read_resource` → `close_dataset`。
- 常用查询（示例 APK：`analysis\heybox_now.apk`，114 MB，82k 类）：

  ```powershell
  $exe = 'E:\Tools\DexSQL\v0.1.0\dexsql.exe'
  $apk = "E:\Project\BetterHeybox\analysis\heybox_now.apk"

  & $exe query -i $apk -s "SELECT descriptor FROM classes WHERE descriptor LIKE '%MainActivity%' LIMIT 10"
  & $exe query -i $apk -s "SELECT name, descriptor FROM methods WHERE class_descriptor = 'Lcom/max/xiaoheihe/MainActivity;' LIMIT 20"
  & $exe query -i $apk -s "SELECT method, callee FROM method_calls WHERE method LIKE '%MainActivity%onCreate%' LIMIT 10"
  & $exe query -i $apk -s "SELECT string_idx, value FROM strings WHERE value LIKE '%api.xiaoheihe.cn%' LIMIT 5"
  & $exe decode -i $apk --method 'Lcom/max/xiaoheihe/MainActivity;->onCreate(Landroid/os/Bundle;)V'
  ```

- 注意：`classes` 表的列是 `descriptor`（不是 `class_descriptor`），`methods`/`fields`
  才有 `class_descriptor`；描述符必须精确（`Lcom/max/xiaoheihe/...;`）。
  想不起确切名字时先 `LIKE` 探索，再精确过滤。
- 只在需要可浏览的完整反编译树时才用 apktool / jadx，并输出到 `analysis/` 或
  `_apk_analysis/`（两者均被 `.gitignore` 忽略）。
- 版本相关结论必须写明所查询的 APK 路径/版本；旧反编译目录中的结论不能直接套到新版本。

## 产物与文档约定

- 分析产物 / APK / 反编译输出：`analysis/`、`_apk_analysis/`（已 gitignore，不提交）。
- 分析报告沿用现有命名：`analysis/逆向分析报告_<版本>.md`、`analysis/<主题>_分析.md`
  （中文正文，附上关键查询与精确描述符）。
- 根目录的 `Plan.md`、`docs/`、`README.md` 为设计/说明文档，改动前先读上下文。
- 不提交：keystore、`local.properties`、`scripts/`、`dist/`、APK 文件。

## 验证

- 代码改动至少通过 `gradlew.bat assembleDebug`（Windows）。
- 涉及运行时行为的结论，需说明是通过静态分析、dexsql 查询还是真机验证得到的。
