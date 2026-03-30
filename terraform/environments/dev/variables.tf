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
