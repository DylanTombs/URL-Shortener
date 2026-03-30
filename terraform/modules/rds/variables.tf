variable "name" { type = string }
variable "environment" { type = string }

variable "private_subnet_ids" {
  description = "Private subnet IDs for the DB subnet group"
  type        = list(string)
}

variable "security_group_id" {
  description = "RDS security group ID (from vpc module)"
  type        = string
}

variable "db_name" {
  description = "PostgreSQL database name"
  type        = string
  default     = "urlshortener"
}

variable "db_username" {
  description = "PostgreSQL master username"
  type        = string
  default     = "urlshortener_admin"
}

variable "instance_class" {
  description = "RDS primary instance class"
  type        = string
  default     = "db.t3.micro"
}

variable "replica_instance_class" {
  description = "RDS read replica instance class"
  type        = string
  default     = "db.t3.micro"
}

variable "allocated_storage" {
  description = "Initial allocated storage in GiB"
  type        = number
  default     = 20
}

variable "max_allocated_storage" {
  description = "Maximum auto-scaled storage in GiB"
  type        = number
  default     = 100
}

variable "multi_az" {
  description = "Enable Multi-AZ deployment on the primary (recommended for prod)"
  type        = bool
  default     = false
}

variable "create_read_replica" {
  description = "Whether to create a read replica"
  type        = bool
  default     = true
}

variable "tags" {
  type    = map(string)
  default = {}
}
