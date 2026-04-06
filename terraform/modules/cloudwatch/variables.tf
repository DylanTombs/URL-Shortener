variable "name" {
  description = "Base name used for all CloudWatch resources"
  type        = string
}

variable "environment" {
  description = "Deployment environment (dev, prod)"
  type        = string
}

variable "alb_arn_suffix" {
  description = "ALB ARN suffix (the part after 'app/') — used in ALB CloudWatch dimensions"
  type        = string
}

variable "target_group_arn_suffix" {
  description = "Target group ARN suffix — used in ALB CloudWatch dimensions"
  type        = string
}

variable "rds_instance_identifier" {
  description = "RDS primary instance identifier — used in RDS CloudWatch dimensions"
  type        = string
}

variable "ecs_cluster_name" {
  description = "ECS cluster name — used in ECS CloudWatch dimensions"
  type        = string
}

variable "ecs_service_name" {
  description = "ECS service name — used in ECS CloudWatch dimensions"
  type        = string
}

variable "log_group_name" {
  description = "CloudWatch log group where application logs are written"
  type        = string
}

variable "alarm_email" {
  description = "Email address to receive alarm notifications"
  type        = string
  default     = ""
}
