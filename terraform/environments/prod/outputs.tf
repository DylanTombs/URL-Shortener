output "alb_dns_name" {
  description = "ALB DNS name — create a Route53 ALIAS or CNAME record pointing here"
  value       = module.alb.alb_dns_name
}

output "alb_zone_id" {
  description = "ALB hosted zone ID for Route53 alias records"
  value       = module.alb.alb_zone_id
}

output "ecr_repository_url" {
  description = "ECR repository URL — needed for Phase 3 CI/CD setup"
  value       = module.ecs.ecr_repository_url
}

output "ecs_cluster_name" {
  value = module.ecs.cluster_name
}

output "ecs_service_name" {
  value = module.ecs.service_name
}

output "cloudwatch_log_group" {
  value = module.ecs.cloudwatch_log_group
}

output "rds_secret_arn" {
  value     = module.rds.secret_arn
  sensitive = true
}

output "cloudwatch_dashboard" {
  description = "CloudWatch dashboard name — open in AWS console to view all metrics"
  value       = module.cloudwatch.dashboard_name
}

output "alarm_sns_topic_arn" {
  description = "SNS topic ARN for CloudWatch alarm notifications"
  value       = module.cloudwatch.sns_topic_arn
}
