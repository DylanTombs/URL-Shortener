variable "aws_region" {
  description = "AWS region"
  type        = string
  default     = "us-east-1"
}

variable "certificate_arn" {
  description = "ACM certificate ARN for HTTPS. Leave empty for HTTP-only (fine for dev)."
  type        = string
  default     = ""
}

variable "app_base_url" {
  description = "Public base URL for shortened links (e.g. https://dev.sho.rt)"
  type        = string
  default     = "https://sho.rt"
}
