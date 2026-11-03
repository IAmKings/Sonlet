# 仓库约定

- 所有 Git 提交的标题与说明使用中文；文件名、API 名和必要的技术术语可保留原文。提交前确认实际提交信息为中文，不使用纯英文标题。
- 本地提交检查通过 `git config core.hooksPath .githooks` 启用；钩子仅检查标题含汉字，说明正文仍须遵守中文约定。
- 修改界面或交互前读取根目录 `DESIGN.md`；修改语音业务前读取 `docs/Hex_Android_Offline_ASR_PRD_v1.0.md`。文档中的目标与待验证项须保留其状态，实施范围以用户本轮请求为准。

<!-- CODEGRAPH_START -->
## CodeGraph

In repositories indexed by CodeGraph (a `.codegraph/` directory exists at the repo root), reach for it BEFORE grep/find or reading files when you need to understand or locate code:

- **MCP tools** (when available): `codegraph_explore` answers most code questions in one call — the relevant symbols' verbatim source plus the call paths between them. `codegraph_node` returns one symbol's source + callers, or reads a whole file with line numbers. If the tools are listed but deferred, load them by name via tool search.
- **Shell** (always works): `codegraph explore "<symbol names or question>"` and `codegraph node <symbol-or-file>` print the same output.

If there is no `.codegraph/` directory, skip CodeGraph entirely — indexing is the user's decision.
<!-- CODEGRAPH_END -->
