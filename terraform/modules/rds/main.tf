terraform {
  required_providers {
    random = {
      source  = "hashicorp/random"
      version = "~> 3.0"
    }
  }
}

# ── Credentials ────────────────────────────────────────────────────────────────

resource "random_password" "db" {
  length           = 32
  special          = true
  override_special = "!#$%&*()-_=+[]?"
}

resource "aws_secretsmanager_secret" "db" {
  name = "${var.name}-${var.environment}-db-credentials"

  # Prod: 30-day recovery window. Dev: immediate deletion (no recovery window).
  recovery_window_in_days = var.environment == "prod" ? 30 : 0

  tags = merge(var.tags, { Name = "${var.name}-${var.environment}-db-credentials" })
}

resource "aws_secretsmanager_secret_version" "db" {
  secret_id = aws_secretsmanager_secret.db.id
  secret_string = jsonencode({
    username = var.db_username
    password = random_password.db.result
  })
}

# ── Subnet + Parameter Groups ─────────────────────────────────────────────────

resource "aws_db_subnet_group" "main" {
  name       = "${var.name}-${var.environment}-db-subnet"
  subnet_ids = var.private_subnet_ids
  tags       = var.tags
}

resource "aws_db_parameter_group" "main" {
  name   = "${var.name}-${var.environment}-pg16"
  family = "postgres16"

  # Log slow queries (>1 s) — feeds Phase 4 CloudWatch metrics
  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }

  parameter {
    name  = "log_connections"
    value = "1"
  }

  tags = var.tags
}

# ── Enhanced Monitoring Role ───────────────────────────────────────────────────

resource "aws_iam_role" "rds_monitoring" {
  name = "${var.name}-${var.environment}-rds-monitoring"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "monitoring.rds.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "rds_monitoring" {
  role       = aws_iam_role.rds_monitoring.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

# ── Primary Instance ───────────────────────────────────────────────────────────

resource "aws_db_instance" "primary" {
  identifier        = "${var.name}-${var.environment}-primary"
  engine            = "postgres"
  engine_version    = "16.3"
  instance_class    = var.instance_class
  allocated_storage = var.allocated_storage
  max_allocated_storage = var.max_allocated_storage
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = var.db_name
  username = var.db_username
  password = random_password.db.result

  db_subnet_group_name   = aws_db_subnet_group.main.name
  parameter_group_name   = aws_db_parameter_group.main.name
  vpc_security_group_ids = [var.security_group_id]

  multi_az            = var.multi_az
  publicly_accessible = false

  # Prod: protect against accidental destroy. Dev: allow destroy.
  deletion_protection       = var.environment == "prod"
  skip_final_snapshot       = var.environment != "prod"
  final_snapshot_identifier = var.environment == "prod" ? "${var.name}-${var.environment}-final" : null

  backup_retention_period = var.environment == "prod" ? 7 : 1
  backup_window           = "03:00-04:00"
  maintenance_window      = "Mon:04:00-Mon:05:00"

  performance_insights_enabled = true
  monitoring_interval          = 60
  monitoring_role_arn          = aws_iam_role.rds_monitoring.arn

  tags = merge(var.tags, { Name = "${var.name}-${var.environment}-primary" })

  lifecycle {
    # Prevent password drift — Secrets Manager owns the password after first apply
    ignore_changes = [password]
  }
}

# ── Read Replica ───────────────────────────────────────────────────────────────
# Handles all GET /{code} reads. Reads outnumber writes by orders of magnitude
# on a URL shortener — this is correct architecture, not premature optimisation.

resource "aws_db_instance" "replica" {
  count               = var.create_read_replica ? 1 : 0
  identifier          = "${var.name}-${var.environment}-replica"
  replicate_source_db = aws_db_instance.primary.identifier
  instance_class      = var.replica_instance_class
  storage_encrypted   = true
  publicly_accessible = false

  vpc_security_group_ids = [var.security_group_id]

  skip_final_snapshot          = true
  performance_insights_enabled = var.environment == "prod"

  tags = merge(var.tags, { Name = "${var.name}-${var.environment}-replica" })
}
