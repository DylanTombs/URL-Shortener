variable "name" { type = string }
variable "environment" { type = string }

variable "alb_arn" {
  description = "ALB ARN to associate the WAF WebACL with"
  type        = string
}

variable "tags" {
  type    = map(string)
  default = {}
}
