# ── 網路：一個公有子網的最小 VPC ──────────────────────────────────────────────
# 只有一台機器、沒有 ALB，不需要跨兩個可用區。
# 刻意不建 NAT Gateway（每月固定費用，作品集用不到）：機器直接掛公有 IP 出網，
# 安全邊界收在 security group，只開 80/443。
resource "aws_vpc" "main" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = "${var.project}-vpc"
  }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${var.project}-igw"
  }
}

# GPU 機型不是每個可用區都有，寫死 "<region>a" 可能開不出機器。
# 改問 AWS 這個機型在哪些可用區有，挑第一個（排序過，每次 plan 結果一樣）。
data "aws_ec2_instance_type_offerings" "gpu" {
  location_type = "availability-zone"

  filter {
    name   = "instance-type"
    values = [var.instance_type]
  }
}

locals {
  availability_zone = sort(data.aws_ec2_instance_type_offerings.gpu.locations)[0]
}

resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = cidrsubnet(aws_vpc.main.cidr_block, 8, 0)
  availability_zone       = local.availability_zone
  map_public_ip_on_launch = true

  tags = {
    Name = "${var.project}-public"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name = "${var.project}-public"
  }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}
