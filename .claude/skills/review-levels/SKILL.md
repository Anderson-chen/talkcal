---
name: review-levels
description: 開 PR 前的審查分級表：改到什麼要審多深，修改者、審核者各執行哪些審查指令。由 `pr-author`、`pr-reviewer` 用定義檔的 `skills:` 預先載入；主 session 呼叫 agent 的順序見 `open-pr`。
---

# 審查分級

這份跟 `open-pr` 分開放：`open-pr` 設了 `disable-model-invocation: true`，定義檔的 `skills:` 就預先載入不了。修改者、審核者要用的分級表放在這份，`open-pr` 只寫主 session 的步驟。

審多深看改到什麼，一個 PR 跨好幾級就照最高的那級。出錯的代價不同：改一行文件跟改部署流程審得一樣深，前者浪費、後者不夠。

| 級 | 改到什麼 | 修改者在第一輪回報前執行 | 審核者執行 |
|---|---|---|---|
| 1 | 只有文件、規則、模板 | 不用 | `/code-review medium <分支>` |
| 2 | 程式碼（含腳本、workflow） | `/simplify <分支>` | `/code-review high <分支>` |
| 3 | `infra/`、`deploy/`、`ops/`、`web/nginx.conf`、`backend/src/main/resources/db/migration/`，或後端的 `auth/` 模組、各模組的 `adapter/in/web/`（對外 API） | `/simplify <分支>` | `/code-review high <分支>` 和 `/security-review` |

- `/code-review` 找 bug，文件寫錯路徑、說錯行為也算；等級要寫明，不寫會沿用上次輸入的等級。
- `/simplify` 找重複、可以簡化的地方，會直接改檔案，所以由修改者執行，審核者才審得到最後進 PR 的那一版。
  - 先 commit 再執行，改到的檔案另外 commit：`/simplify` 讀的是已經 commit 的 diff。
  - 分支名稱要給：還沒 push 的分支沒有 upstream，不給的話 `/simplify` 只讀最後一個 commit 的 diff。
- `/security-review` 不接受分支參數，審的是工作目錄 checkout 的分支。
- 改到中文的分支，審核者另外做文案審查：呼叫 `copy-reviewer`（[copy-reviewer.md](../../agents/copy-reviewer.md)），只給分支名稱。寫的人重看自己的句子，會照寫的時候的意思去讀，讀不出哪裡不通順。
- 第 3 級裡出錯難收拾的（資料遷移、刪資源），由人手動執行 `/code-review ultra`：這是雲端多 agent 審查、另外計費，只能由人啟動。

## 下一步

修改者照這張表執行列給修改者的指令後回報；審核者照這張表審完後回報。主 session 接著照 [open-pr skill](../open-pr/SKILL.md) 的順序做。
