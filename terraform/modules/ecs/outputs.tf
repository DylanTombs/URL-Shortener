output "cluster_name" {
  description = "ECS cluster name (used by CD pipeline for deployments)"
  value       = aws_ecs_cluster.main.name
}

output "cluster_arn" {
  value = aws_ecs_cluster.main.arn
}

output "service_name" {
  description = "ECS service name (used by CD pipeline for deployments)"
  value       = aws_ecs_service.app.name
}

output "ecr_repository_url" {
  description = "ECR repository URL — push images here: docker push <url>:<tag>"
  value       = aws_ecr_repository.app.repository_url
}

output "task_definition_arn" {
  description = "Current task definition ARN"
  value       = aws_ecs_task_definition.app.arn
}

output "cloudwatch_log_group" {
  description = "CloudWatch log group name for application logs"
  value       = aws_cloudwatch_log_group.app.name
}
