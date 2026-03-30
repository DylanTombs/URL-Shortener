resource "aws_elasticache_subnet_group" "main" {
  name       = "${var.name}-${var.environment}-cache-subnet"
  subnet_ids = var.private_subnet_ids
  tags       = var.tags
}

resource "aws_elasticache_replication_group" "main" {
  replication_group_id = "${var.name}-${var.environment}"
  description          = "Redis cache for ${var.name} ${var.environment}"

  engine         = "redis"
  engine_version = "7.1"
  port           = 6379
  node_type      = var.node_type

  # num_cache_clusters=1 → single node (dev)
  # num_cache_clusters=2 → primary + 1 replica across AZs (prod)
  num_cache_clusters = var.num_cache_clusters

  subnet_group_name  = aws_elasticache_subnet_group.main.name
  security_group_ids = [var.security_group_id]

  at_rest_encryption_enabled = true
  # transit_encryption_enabled left false — enabling requires TLS config in
  # Spring Boot's Lettuce client, addressed in Phase 4 hardening.
  transit_encryption_enabled = false

  # Failover requires at least 2 cache clusters
  automatic_failover_enabled = var.num_cache_clusters > 1
  multi_az_enabled           = var.num_cache_clusters > 1

  snapshot_retention_limit = var.environment == "prod" ? 1 : 0
  snapshot_window          = "02:00-03:00"

  tags = merge(var.tags, { Name = "${var.name}-${var.environment}-redis" })
}
