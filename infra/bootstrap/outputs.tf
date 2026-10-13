output "state_bucket" {
  description = "各階段的 backend.hcl 填這個 bucket 名稱"
  value       = aws_s3_bucket.tfstate.bucket
}
