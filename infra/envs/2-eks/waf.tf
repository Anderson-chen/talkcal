# ── WAF：限流從 web/nginx.conf 搬到這裡（ADR 0005、#12、#103）────────────────────
# EKS 上沒有 nginx 那層，同樣三道限制改用 WAF 的 rate-based rule，掛在 CloudFront 上。
# 好處：WAF 看到的就是使用者的 IP（nginx 要靠 X-Forwarded-For 推）；被擋的請求不會進到 ALB、更不會叫到模型。
# 改這裡的數字時，web/nginx.conf 要一起改（本機、1-ec2 用的是那份）。
#
# 每月約 5 USD（web ACL）+ 每條規則 1 USD + 每百萬請求 0.6 USD。
locals {
  rate_limited_body = {
    assistant = jsonencode({ error = "問得太快了，請等一分鐘再試" })
    auth      = jsonencode({ error = "嘗試太多次了，請等一分鐘再試" })
  }
}

resource "aws_wafv2_web_acl" "main" {
  provider = aws.us_east_1 # CloudFront 的 WAF 一定在 us-east-1
  name     = "${local.name}-eks"
  scope    = "CLOUDFRONT"

  default_action {
    allow {}
  }

  # 被擋時回的內容，格式跟 nginx 的 @rate_limited 一樣（{"error": ...}），前端照常顯示
  custom_response_body {
    key          = "assistant"
    content      = local.rate_limited_body.assistant
    content_type = "APPLICATION_JSON"
  }

  custom_response_body {
    key          = "auth"
    content      = local.rate_limited_body.auth
    content_type = "APPLICATION_JSON"
  }

  # 1. AI 助理發問，每個 IP 每分鐘 10 次（= nginx 的 assistant_per_ip）
  rule {
    name     = "assistant-per-ip"
    priority = 1

    action {
      block {
        custom_response {
          response_code            = 429
          custom_response_body_key = "assistant"
          response_header {
            name  = "Retry-After"
            value = "60"
          }
        }
      }
    }

    statement {
      rate_based_statement {
        limit                 = 10
        evaluation_window_sec = 60
        aggregate_key_type    = "IP"

        scope_down_statement {
          and_statement {
            statement {
              byte_match_statement {
                search_string         = "POST"
                positional_constraint = "EXACTLY"
                field_to_match {
                  method {}
                }
                text_transformation {
                  priority = 0
                  type     = "NONE"
                }
              }
            }
            statement {
              byte_match_statement {
                search_string         = "/api/calendar/assistant"
                positional_constraint = "EXACTLY"
                field_to_match {
                  uri_path {}
                }
                text_transformation {
                  priority = 0
                  type     = "NONE"
                }
              }
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "assistant-per-ip"
      sampled_requests_enabled   = true
    }
  }

  # 2. AI 助理發問，全站每分鐘 30 次（= nginx 的 assistant_all）：換 IP 也繞不過，是帳單的總上限。
  #    聚合鍵 CONSTANT：所有符合的請求算在同一個計數器
  rule {
    name     = "assistant-all"
    priority = 2

    action {
      block {
        custom_response {
          response_code            = 429
          custom_response_body_key = "assistant"
          response_header {
            name  = "Retry-After"
            value = "60"
          }
        }
      }
    }

    statement {
      rate_based_statement {
        limit                 = 30
        evaluation_window_sec = 60
        aggregate_key_type    = "CONSTANT"

        scope_down_statement {
          and_statement {
            statement {
              byte_match_statement {
                search_string         = "POST"
                positional_constraint = "EXACTLY"
                field_to_match {
                  method {}
                }
                text_transformation {
                  priority = 0
                  type     = "NONE"
                }
              }
            }
            statement {
              byte_match_statement {
                search_string         = "/api/calendar/assistant"
                positional_constraint = "EXACTLY"
                field_to_match {
                  uri_path {}
                }
                text_transformation {
                  priority = 0
                  type     = "NONE"
                }
              }
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "assistant-all"
      sampled_requests_enabled   = true
    }
  }

  # 3. 登入、註冊，每個 IP 每分鐘 10 次。nginx 是 5 次，但 WAF 的 rate limit 最低就是 10（ADR 0005 的代價）
  rule {
    name     = "auth-per-ip"
    priority = 3

    action {
      block {
        custom_response {
          response_code            = 429
          custom_response_body_key = "auth"
          response_header {
            name  = "Retry-After"
            value = "60"
          }
        }
      }
    }

    statement {
      rate_based_statement {
        limit                 = 10
        evaluation_window_sec = 60
        aggregate_key_type    = "IP"

        scope_down_statement {
          and_statement {
            statement {
              byte_match_statement {
                search_string         = "POST"
                positional_constraint = "EXACTLY"
                field_to_match {
                  method {}
                }
                text_transformation {
                  priority = 0
                  type     = "NONE"
                }
              }
            }
            statement {
              # /api/auth/login 或 /api/auth/register
              regex_match_statement {
                regex_string = "^/api/auth/(login|register)$"
                field_to_match {
                  uri_path {}
                }
                text_transformation {
                  priority = 0
                  type     = "NONE"
                }
              }
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "auth-per-ip"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${local.name}-eks"
    sampled_requests_enabled   = true
  }
}
