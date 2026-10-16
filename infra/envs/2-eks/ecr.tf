# ── ECR：GitHub Actions 推、節點拉（ADR 0004）─────────────────────────────────
# 1-ec2 在機器上 build；EKS 的節點會換、會有好幾台，映像檔改成 CI build 一次、推到這裡。
# 只建 EKS 上真的會跑的：app；gpu 時再加 llama-server。web 不建——前端在 EKS 上是 S3 送（ADR 0005）。
locals {
  ecr_repos = concat(["app"], local.gpu ? ["llama-server"] : [])
}

resource "aws_ecr_repository" "main" {
  for_each = toset(local.ecr_repos)
  name     = "${local.name}/${each.value}"

  # tag 不可覆寫：同一個版本號（cd/version.sh，#25）永遠是同一份映像檔，退版拿到的就是當初那份
  image_tag_mutability = "IMMUTABLE"

  # 推上來就掃一次（基本掃描免費）。CI 已經用 Trivy 擋過 HIGH、CRITICAL（ci/vulns.sh），這是第二道
  image_scanning_configuration {
    scan_on_push = true
  }

  # 平常 destroy：裡面有映像檔也直接刪（不然 destroy 會卡住）。映像檔可以從 git 重新 build
  force_delete = true
}

# 只留最近 20 個版本，舊的自動刪：ECR 按 GB 收儲存費，app 映像檔一個約 300 MB
resource "aws_ecr_lifecycle_policy" "main" {
  for_each   = aws_ecr_repository.main
  repository = each.value.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "keep last 20 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 20
      }
      action = { type = "expire" }
    }]
  })
}
