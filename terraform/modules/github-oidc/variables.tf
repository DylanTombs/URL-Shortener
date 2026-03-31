variable "name" {
  description = "Project name prefix for resource naming"
  type        = string
}

variable "environment" {
  description = "Environment name (dev, prod)"
  type        = string
}

variable "github_repo" {
  description = "GitHub repository in owner/repo format (e.g. myorg/url-shortener)"
  type        = string
}

variable "ecr_repository_arn" {
  description = "ARN of the ECR repository the workflow pushes images to"
  type        = string
}

variable "ecs_role_arns" {
  description = "List of IAM role ARNs the GitHub Actions role is allowed to pass to ECS (execution + task roles)"
  type        = list(string)
}

variable "tags" {
  type    = map(string)
  default = {}
}
