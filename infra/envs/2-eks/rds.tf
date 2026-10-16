# ── RDS PostgreSQL：取代 compose 的 postgres 容器 ──────────────────────────────
# 備份、修補、故障轉移交給託管服務（#45）。只有 EKS 叢集的安全群組連得到 5432。
resource "aws_db_subnet_group" "main" {
  name       = local.name
  subnet_ids = aws_subnet.database[*].id
}

resource "aws_security_group" "db" {
  name        = "${local.name}-db"
  description = "PostgreSQL from EKS only"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "db_from_eks" {
  security_group_id            = aws_security_group.db.id
  referenced_security_group_id = aws_eks_cluster.main.vpc_config[0].cluster_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "app pods"
}

# 密碼只在這次 apply 的記憶體裡：ephemeral 不寫進 state，下面用 write-only 參數（*_wo）送給 RDS 和 Secrets Manager。
# 1-ec2 的密碼在 state 裡是明碼（靠 state bucket 加密）；這裡連 state 都沒有。
# 代價：Terraform 不知道密碼變了沒，要換就把 db_password_version 加一（variables.tf）。
ephemeral "random_password" "db" {
  length  = 32
  special = false # 避開 JDBC URL、shell、YAML 的跳脫問題（跟 1-ec2 一樣）
}

resource "aws_db_instance" "main" {
  identifier     = local.name
  engine         = "postgres"
  engine_version = var.db_engine_version
  instance_class = var.db_instance_class

  db_name  = "talkcal"
  username = "talkcal"
  # 不用 manage_master_user_password（RDS 自己放進 Secrets Manager、自動輪替）：
  # 它的祕密名稱是 rds!db-<亂碼>，每次重建都不同，overlay 的 ExternalSecret 就得跟著改；
  # 而且自動輪替後 app 要重啟才拿得到新密碼，現在沒有東西去觸發。先用固定名稱的祕密
  password_wo         = ephemeral.random_password.db.result
  password_wo_version = var.db_password_version

  allocated_storage     = 20
  max_allocated_storage = 50 # 快滿時自動長，最多到 50 GB
  storage_type          = "gp3"
  storage_encrypted     = true

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]
  multi_az               = var.db_multi_az
  publicly_accessible    = false

  backup_retention_period    = 7
  auto_minor_version_upgrade = true
  # 作品集平常 destroy：不留最後快照、不開刪除保護。真的有資料要留時兩個都反過來（#45）
  skip_final_snapshot = true
  deletion_protection = false

  # 慢查詢之類的分析：Performance Insights 免費層 7 天
  performance_insights_enabled = true
}

# DB 連線資訊，External Secrets 轉成 k8s Secret talkcal-db（password 鍵名跟 #56 的 base 一樣）
resource "aws_secretsmanager_secret" "db" {
  name        = "${local.name}/db"
  description = "talkcal 的 PostgreSQL 密碼（External Secrets 同步到 k8s Secret talkcal-db）"
  # 平常 destroy 再 apply：預設 7–30 天的復原期內同名祕密建不起來，所以立刻刪
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "db" {
  secret_id = aws_secretsmanager_secret.db.id
  secret_string_wo = jsonencode({
    username = "talkcal"
    password = ephemeral.random_password.db.result
  })
  secret_string_wo_version = var.db_password_version
}
