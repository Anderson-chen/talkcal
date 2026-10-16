# ── CloudFront：使用者唯一看到的網址（ADR 0005）─────────────────────────────────
#   /*      → S3（前端靜態檔，OAC：只有這個 distribution 讀得到）
#   /api/*  → VPC origin → 私有 ALB → app Pod
# 瀏覽器看到的是同一個來源，跟本機的 nginx 一樣，app 不必開 CORS。
# 網址用 CloudFront 給的 *.cloudfront.net（自帶 HTTPS），不必買網域；1-ec2 的 sslip.io 在這裡用不到。

resource "aws_cloudfront_origin_access_control" "web" {
  name                              = "${local.name}-web"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# S3 只准這個 distribution 讀（bucket 本身擋掉所有公開存取，見 s3.tf）
resource "aws_s3_bucket_policy" "web" {
  bucket = local.web_bucket.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "cloudfront.amazonaws.com" }
      Action    = "s3:GetObject"
      Resource  = "${local.web_bucket.arn}/*"
      Condition = {
        StringEquals = { "AWS:SourceArn" = aws_cloudfront_distribution.main.arn }
      }
    }]
  })
}

resource "aws_cloudfront_vpc_origin" "api" {
  vpc_origin_endpoint_config {
    name                   = "${local.name}-api"
    arn                    = aws_lb.api.arn
    http_port              = 80
    https_port             = 443
    origin_protocol_policy = "http-only"

    origin_ssl_protocols {
      items    = ["TLSv1.2"]
      quantity = 1
    }
  }
}

# AWS 代管的政策，用名字查 ID
data "aws_cloudfront_cache_policy" "optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_cache_policy" "disabled" {
  name = "Managed-CachingDisabled"
}

# 把使用者的 header（含 Host、Cookie、Authorization）和 CloudFront 自己加的（CloudFront-Forwarded-Proto、CloudFront-Viewer-Address）
# 全部轉給 ALB：app 用 Host 組 OAuth 回呼網址，用 CloudFront-Forwarded-Proto 知道使用者是 https（overlay 的 Tomcat 設定）
data "aws_cloudfront_origin_request_policy" "all_viewer_and_cf" {
  name = "Managed-AllViewerAndCloudFrontHeaders-2022-06"
}

resource "aws_cloudfront_distribution" "main" {
  enabled             = true
  comment             = "${local.name} (2-eks)"
  default_root_object = "index.html"
  # 只用北美、歐洲、亞洲的節點（含日本、台灣），最便宜的兩個價格等級之一
  price_class  = "PriceClass_200"
  web_acl_id   = aws_wafv2_web_acl.main.arn
  http_version = "http2and3"

  origin {
    origin_id                = "web"
    domain_name              = local.web_bucket.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.web.id
  }

  origin {
    origin_id   = "api"
    domain_name = aws_lb.api.dns_name

    vpc_origin_config {
      vpc_origin_id = aws_cloudfront_vpc_origin.api.id
      # 預設上限 60 秒，app 叫模型最久 2 分鐘：超過會 504（ADR 0005 的代價）
      origin_read_timeout      = var.api_origin_read_timeout
      origin_keepalive_timeout = 5
    }
  }

  # 前端：Vite 的 /assets/* 檔名帶雜湊，快取一年；index.html 由上傳時的 Cache-Control: no-cache 決定每次回來問
  # （跟 web/nginx.conf 的兩條規則一樣，只是 header 改成上傳到 S3 時設，見 .github/workflows/eks-deploy.yml）
  default_cache_behavior {
    target_origin_id       = "web"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    cache_policy_id        = data.aws_cloudfront_cache_policy.optimized.id
    compress               = true
  }

  ordered_cache_behavior {
    path_pattern             = "/api/*"
    target_origin_id         = "api"
    viewer_protocol_policy   = "https-only"
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    cache_policy_id          = data.aws_cloudfront_cache_policy.disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer_and_cf.id
    compress                 = true
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }
}
