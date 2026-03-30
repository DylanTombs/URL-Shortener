variable "aws_region" {
  description = "AWS region"
  type        = string
  default     = "us-east-1"
}

variable "certificate_arn" {
  description = "ACM certificate ARN for HTTPS (required for prod)"
  type        = string
  default     = ""
}

variable "app_base_url" {
  description = "Public base URL for shortened links (e.g. https://sho.rt)"
  type        = string
  default     = "https://sho.rt"
}
