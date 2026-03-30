variable "name" { type = string }
variable "environment" { type = string }

variable "private_subnet_ids" {
  description = "Private subnet IDs for ECS tasks"
  type        = list(string)
}

variable "security_group_id" {
  description = "ECS security group ID (from vpc module)"
  type        = string
}

variable "target_group_arn" {
  description = "ALB target group ARN to register ECS tasks with"
  type        = string
}

variable "db_primary_endpoint" {
  description = "RDS primary endpoint hostname (no port)"
  type        = string
}

variable "db_replica_endpoint" {
  description = "RDS replica endpoint hostname (no port). Falls back to primary if no replica."
  type        = string
}

variable "db_name" {
  description = "PostgreSQL database name"
  type        = string
  default     = "urlshortener"
}

variable "db_secret_arn" {
  description = "Secrets Manager ARN for DB credentials ({username, password} JSON)"
  type        = string
}

variable "redis_endpoint" {
  description = "ElastiCache primary endpoint hostname (no port)"
  type        = string
}

variable "task_cpu" {
  description = "Fargate task CPU units (256, 512, 1024, 2048, 4096)"
  type        = number
  default     = 256
}

variable "task_memory" {
  description = "Fargate task memory in MiB"
  type        = number
  default     = 512
}

variable "desired_count" {
  description = "Initial desired task count (ignored after first deploy — CD pipeline owns this)"
  type        = number
  default     = 1
}

variable "min_capacity" {
  description = "Auto-scaling minimum task count"
  type        = number
  default     = 1
}

variable "max_capacity" {
  description = "Auto-scaling maximum task count"
  type        = number
  default     = 4
}

variable "log_retention_days" {
  description = "CloudWatch log retention in days"
  type        = number
  default     = 7
}

variable "tags" {
  type    = map(string)
  default = {}
}
