output "primary_endpoint" {
  description = "Redis primary endpoint hostname (no port) — passed to ECS as SPRING_DATA_REDIS_HOST"
  value       = aws_elasticache_replication_group.main.primary_endpoint_address
}

output "port" {
  description = "Redis port"
  value       = aws_elasticache_replication_group.main.port
}

output "replication_group_id" {
  value = aws_elasticache_replication_group.main.id
}
