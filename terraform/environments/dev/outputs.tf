output "alb_dns_name" {
  description = "Hit this URL to test the app: http://<alb_dns_name>/actuator/health"
  value       = module.alb.alb_dns_name
}

output "ecr_repository_url" {
  description = "Push Docker images here — needed for Phase 3 CI/CD setup"
  value       = module.ecs.ecr_repository_url
}

output "ecs_cluster_name" {
  description = "ECS cluster name — needed for CD pipeline"
  value       = module.ecs.cluster_name
}

output "ecs_service_name" {
  description = "ECS service name — needed for CD pipeline"
  value       = module.ecs.service_name
}

output "cloudwatch_log_group" {
  description = "CloudWatch log group for application logs"
  value       = module.ecs.cloudwatch_log_group
}

output "rds_secret_arn" {
  description = "Secrets Manager ARN for DB credentials"
  value       = module.rds.secret_arn
  sensitive   = true
}
