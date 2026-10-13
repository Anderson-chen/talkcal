provider "aws" {
  region  = var.region
  profile = var.aws_profile

  default_tags {
    tags = {
      Project     = var.project
      Environment = "1-ec2"
      ManagedBy   = "terraform"
    }
  }
}

data "aws_caller_identity" "current" {}
