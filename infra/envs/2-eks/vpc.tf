# ── 網路：兩個可用區，每區三層子網 ────────────────────────────────────────────
# 跟 1-ec2 不同：ALB 和 EKS 都要求至少兩個可用區，一台節點掛了另一區還有。
#
#   公有   10.1.0.0/24、10.1.1.0/24     NAT Gateway（ALB 是私有的，不放這裡）
#   私有   10.1.16.0/20、10.1.32.0/20   EKS 節點、Pod、私有 ALB。/20 是因為 VPC CNI 讓每個 Pod 拿一個 VPC 的 IP
#   資料庫 10.1.8.0/24、10.1.9.0/24     RDS，沒有往外的路由
locals {
  name = var.project
  gpu  = var.model_backend == "gpu"

  # 排序過、取前兩個：每次 plan 結果一樣。GPU 機型不是每區都有，gpu 時從有 g6 的可用區挑
  azs = slice(sort(local.gpu ? data.aws_ec2_instance_type_offerings.gpu[0].locations : data.aws_availability_zones.available.names), 0, 2)
}

data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_ec2_instance_type_offerings" "gpu" {
  count         = local.gpu ? 1 : 0
  location_type = "availability-zone"

  filter {
    name   = "instance-type"
    values = [var.gpu_instance_type]
  }
}

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true # RDS 的端點、VPC endpoint 的私有 DNS 都要

  tags = { Name = "${local.name}-eks" }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${local.name}-eks" }
}

resource "aws_subnet" "public" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index)
  availability_zone = local.azs[count.index]

  tags = { Name = "${local.name}-public-${local.azs[count.index]}" }
}

resource "aws_subnet" "private" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, count.index + 1)
  availability_zone = local.azs[count.index]

  tags = {
    Name = "${local.name}-private-${local.azs[count.index]}"
    # ALB controller 自己建 internal load balancer 時找子網用。這裡的 ALB 是 Terraform 建的，
    # 留著是為了之後改用 Ingress / Gateway API 時不必回來補
    "kubernetes.io/role/internal-elb" = "1"
  }
}

resource "aws_subnet" "database" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index + 8)
  availability_zone = local.azs[count.index]

  tags = { Name = "${local.name}-database-${local.azs[count.index]}" }
}

# ── 出網：NAT Gateway ───────────────────────────────────────────────────────
# 節點在私有子網，要拉映像檔（ECR 以外的，例如 helm chart 的映像檔）、叫 Bedrock、跟 EKS API 講話都要出網。
# 預設兩區共用一個（var.nat_per_az），省一個 NAT 每月約 45 USD。
locals {
  nat_count = var.nat_per_az ? 2 : 1
}

resource "aws_eip" "nat" {
  count  = local.nat_count
  domain = "vpc"
  tags   = { Name = "${local.name}-nat-${count.index}" }
}

resource "aws_nat_gateway" "main" {
  count         = local.nat_count
  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id
  tags          = { Name = "${local.name}-nat-${count.index}" }

  depends_on = [aws_internet_gateway.main]
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "${local.name}-public" }
}

resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table" "private" {
  count  = 2
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    # 共用一個 NAT 時兩區都指到第 0 個
    nat_gateway_id = aws_nat_gateway.main[var.nat_per_az ? count.index : 0].id
  }

  tags = { Name = "${local.name}-private-${local.azs[count.index]}" }
}

resource "aws_route_table_association" "private" {
  count          = 2
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private[count.index].id
}

# 資料庫子網：沒有 0.0.0.0/0，RDS 出不了網、外面也進不來
resource "aws_route_table" "database" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${local.name}-database" }
}

resource "aws_route_table_association" "database" {
  count          = 2
  subnet_id      = aws_subnet.database[count.index].id
  route_table_id = aws_route_table.database.id
}

# ── S3 gateway endpoint：免費，拉 ECR 映像檔的層、讀模型、觀測寫區塊都不經過 NAT ──────
# ECR 的映像檔層實際上存在 S3，所以這一個就省掉大部分的 NAT 流量費。
# ECR API、STS 等 interface endpoint 每個每區約 0.014 USD/h（兩區一個月約 20 USD），比省下的 NAT 流量費還貴，先不建。
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = aws_route_table.private[*].id

  tags = { Name = "${local.name}-s3" }
}
