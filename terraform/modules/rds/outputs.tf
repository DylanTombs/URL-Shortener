output "primary_endpoint" {
  description = "RDS primary endpoint hostname (write endpoint)"
  value       = aws_db_instance.primary.address
}

output "replica_endpoint" {
  description = "RDS replica endpoint hostname (read endpoint). Falls back to primary if no replica."
  value       = var.create_read_replica ? aws_db_instance.replica[0].address : aws_db_instance.primary.address
}

output "secret_arn" {
  description = "Secrets Manager ARN for DB credentials — passed to ECS execution role"
  value       = aws_secretsmanager_secret.db.arn
}

output "db_name" {
  value = var.db_name
}

output "primary_identifier" {
  description = "RDS primary instance identifier — used as CloudWatch dimension for AWS/RDS metrics"
  value       = aws_db_instance.primary.identifier
}
