# 0005. 前端走 CloudFront + S3，`/api` 經 VPC origin 進私有 ALB，限流搬到 WAF

- 狀態：已採用
- 日期：2026-10-16
- 相關：[#58](https://github.com/Anderson-chen/talkcal/issues/58)、[#12 濫用防護](https://github.com/Anderson-chen/talkcal/issues/12)、[#103 登入限流](https://github.com/Anderson-chen/talkcal/issues/103)、`web/nginx.conf`

## 背景

本機和 1-ec2 的入口是 `web`（nginx）：送 Vue 靜態檔、把 `/api` 轉給 app，而且**限流寫在 nginx**（AI 助理每 IP 每分鐘 10 次、全站 30 次；登入、註冊每 IP 5 次）。
規劃上 EKS 改成 CloudFront + S3 送前端、`/api/*` 轉 ALB。要決定：nginx 那層還留不留、限流搬去哪、ALB 要不要對外。

## 選項

| 選項 | 好處 | 代價 |
|---|---|---|
| A. CloudFront → 公開 ALB → web（nginx）→ app，前端也從 nginx 送 | 跟本機同一個映像檔、限流不用搬 | ALB 對外，要另外擋「繞過 CloudFront 直接打 ALB」（自訂 header 或 prefix list）；nginx 要改信任的 proxy 網段才拿得到真實 IP；靜態檔還是從叢集送 |
| B. CloudFront + S3 送前端；`/api/*` 經 **VPC origin** 進**私有** ALB → app；限流改用 WAF 的 rate-based rule | ALB 在私有子網、沒有公開 IP，根本繞不過 CloudFront；靜態檔不經過叢集；WAF 看得到真實的使用者 IP | 限流規則要用 WAF 重寫一次，WAF 最低門檻是 10 次／窗，登入那條從 5 次變 10 次；WAF 每月約 8 USD |

查證（2026）：CloudFront 可以直接把私有子網裡的 ALB 當 origin（VPC origin），不必把 ALB 開到網際網路；
CloudFront 的固定費率方案（含 WAF、免費層 0 USD/月）也可以考慮（[AWS CloudFront 價格頁](https://aws.amazon.com/cloudfront/pricing/)、[cloudburn](https://cloudburn.io/blog/amazon-cloudfront-pricing)）。
Ingress NGINX 已在 2026-03 退役（[Kubernetes 聲明](https://kubernetes.io/blog/2026/01/29/ingress-nginx-statement/)），所以叢集內也不再用 Ingress NGINX 當入口。

## 決定

**選 B。**

- 前端：`web/` 的 `npm run build` 產出直接同步到 S3（只准 CloudFront 用 OAC 讀），`index.html` 帶 `no-cache`、`/assets/*` 快取一年（跟 nginx.conf 的兩條規則一樣）。
- `/api/*`：CloudFront → VPC origin → 私有 ALB（Terraform 建）→ app Pod。ALB 和 target group 由 Terraform 管，
  Pod 的 IP 由 AWS Load Balancer Controller 的 `TargetGroupBinding` 註冊進去——不用 Ingress，CloudFront 才能在 Terraform 裡直接指到這個 ALB。
- 限流：WAF（掛在 CloudFront 上）三條 rate-based rule，對照 nginx.conf：

  | nginx.conf | WAF | 差別 |
  |---|---|---|
  | `POST /api/calendar/assistant` 每 IP 10r/m | 每 IP 60 秒 10 次 | 一樣 |
  | 同一條全站 30r/m | 聚合鍵 `CONSTANT`，60 秒 30 次 | 一樣 |
  | `POST /api/auth/login`、`/register` 每 IP 5r/m | 每 IP 60 秒 10 次 | WAF 最低 10，比 nginx 寬一倍 |

  被擋時回 429 + `Retry-After: 60` + `{"error":"..."}`，格式跟 nginx 的 `@rate_limited` 一樣，前端不用改。
- EKS 上不部署 `web` 這個 Deployment（overlay 刪掉）。映像檔 `talkcal/web` 照樣給本機和 1-ec2 用。

## 代價

- **CloudFront 等 origin 回應預設最多 60 秒**（`origin_read_timeout`，要更長得開 Service Quotas 申請，上限 180 秒）。
  app 叫模型最久等 2 分鐘，nginx 那邊設的是 180 秒——超過 60 秒的 AI 回答在 EKS 上會變成 504。Bedrock 32B 平常幾秒內回，先接受，README 列在「已知限制」。
- 限流規則寫了兩份（nginx.conf、WAF），改的時候要兩邊一起改。
- `X-Forwarded-Proto`：ALB 的 listener 是 HTTP，會把它設成 `http`，app 組出的 OAuth 回呼網址會變 `http://`。
  改讓 Tomcat 看 CloudFront 帶的 `CloudFront-Forwarded-Proto`（overlay 的環境變數），CloudFront 的 origin request policy 要轉這個 header。

## 什麼時候重想

- AI 回答常常超過 60 秒 → 申請 `origin_read_timeout` 提高到 180，或 `/api/calendar/assistant` 改成串流回應。
- 想要自訂網域 → 加 Route 53 + ACM（us-east-1）憑證，CloudFront 加 alias；現在用 `*.cloudfront.net` 的網址，不必買網域。
