output "sns_topic_arn" {
  description = "SNS topic ARN for alarm notifications"
  value       = aws_sns_topic.alarms.arn
}

output "dashboard_name" {
  description = "CloudWatch dashboard name"
  value       = aws_cloudwatch_dashboard.main.dashboard_name
}

output "redirect_p99_alarm_arn" {
  description = "ARN of the p99 latency alarm"
  value       = aws_cloudwatch_metric_alarm.redirect_p99_latency.arn
}

output "cache_hit_rate_alarm_arn" {
  description = "ARN of the cache hit rate alarm"
  value       = aws_cloudwatch_metric_alarm.cache_hit_rate.arn
}

output "rds_cpu_alarm_arn" {
  description = "ARN of the RDS CPU alarm"
  value       = aws_cloudwatch_metric_alarm.rds_cpu.arn
}

output "alb_5xx_alarm_arn" {
  description = "ARN of the ALB 5xx error rate alarm"
  value       = aws_cloudwatch_metric_alarm.alb_5xx_rate.arn
}
