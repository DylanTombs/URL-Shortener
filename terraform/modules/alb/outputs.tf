output "alb_arn" {
  description = "ALB ARN (needed by WAF module for WebACL association)"
  value       = aws_lb.main.arn
}

output "alb_dns_name" {
  description = "ALB DNS name — point your domain CNAME here"
  value       = aws_lb.main.dns_name
}

output "alb_zone_id" {
  description = "ALB hosted zone ID (for Route53 alias records)"
  value       = aws_lb.main.zone_id
}

output "target_group_arn" {
  description = "Target group ARN — passed to ECS service for load balancer registration"
  value       = aws_lb_target_group.app.arn
}
