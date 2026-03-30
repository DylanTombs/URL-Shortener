variable "name" {
  type = string
}

variable "environment" {
  type = string
}

variable "vpc_id" {
  description = "VPC ID the ALB belongs to"
  type        = string
}

variable "public_subnet_ids" {
  description = "Public subnet IDs for the ALB (must span at least 2 AZs)"
  type        = list(string)
}

variable "security_group_id" {
  description = "Security group ID for the ALB (from vpc module)"
  type        = string
}

variable "certificate_arn" {
  description = "ACM certificate ARN for HTTPS. Leave empty for HTTP-only (dev)."
  type        = string
  default     = ""
}

variable "tags" {
  type    = map(string)
  default = {}
}
