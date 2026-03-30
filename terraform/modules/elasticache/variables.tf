variable "name" { type = string }
variable "environment" { type = string }

variable "private_subnet_ids" {
  description = "Private subnet IDs for the ElastiCache subnet group"
  type        = list(string)
}

variable "security_group_id" {
  description = "ElastiCache security group ID (from vpc module)"
  type        = string
}

variable "node_type" {
  description = "ElastiCache node type"
  type        = string
  default     = "cache.t3.micro"
}

variable "num_cache_clusters" {
  description = "Number of cache nodes. 1 = single node (dev), 2 = primary + replica (prod)."
  type        = number
  default     = 1

  validation {
    condition     = var.num_cache_clusters >= 1 && var.num_cache_clusters <= 6
    error_message = "num_cache_clusters must be between 1 and 6."
  }
}

variable "tags" {
  type    = map(string)
  default = {}
}
