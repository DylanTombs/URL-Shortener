data "aws_region" "current" {}
data "aws_caller_identity" "current" {}

# ── ECR ───────────────────────────────────────────────────────────────────────

resource "aws_ecr_repository" "app" {
  name                 = "${var.name}/${var.environment}"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = merge(var.tags, { Name = "${var.name}-${var.environment}-ecr" })
}

resource "aws_ecr_lifecycle_policy" "app" {
  repository = aws_ecr_repository.app.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Retain last 10 images, expire older ones"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}

# ── CloudWatch Log Group ───────────────────────────────────────────────────────

resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/${var.name}-${var.environment}"
  retention_in_days = var.log_retention_days
  tags              = var.tags
}

# ── IAM — Execution Role ───────────────────────────────────────────────────────
# Used by the ECS agent to pull images from ECR and write logs to CloudWatch.

resource "aws_iam_role" "execution" {
  name = "${var.name}-${var.environment}-ecs-execution"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "execution_secrets" {
  name = "${var.name}-${var.environment}-execution-secrets"
  role = aws_iam_role.execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = [var.db_secret_arn]
    }]
  })
}

# ── IAM — Task Role ────────────────────────────────────────────────────────────
# Used by the running application container.

resource "aws_iam_role" "task" {
  name = "${var.name}-${var.environment}-ecs-task"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = var.tags
}

resource "aws_iam_role_policy" "task_cloudwatch" {
  name = "${var.name}-${var.environment}-task-cloudwatch"
  role = aws_iam_role.task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "cloudwatch:PutMetricData",
        "logs:CreateLogStream",
        "logs:PutLogEvents",
        "logs:DescribeLogStreams"
      ]
      Resource = "*"
    }]
  })
}

# ── ECS Cluster ───────────────────────────────────────────────────────────────

resource "aws_ecs_cluster" "main" {
  name = "${var.name}-${var.environment}"

  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  tags = merge(var.tags, { Name = "${var.name}-${var.environment}-cluster" })
}

resource "aws_ecs_cluster_capacity_providers" "main" {
  cluster_name       = aws_ecs_cluster.main.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
  }
}

# ── ECS Task Definition ────────────────────────────────────────────────────────

locals {
  # CD pipeline will push the real image; this placeholder ensures the task
  # definition is valid on first apply. ignore_changes below prevents
  # terraform from reverting to this placeholder after CD updates it.
  app_image = "${aws_ecr_repository.app.repository_url}:latest"
}

resource "aws_ecs_task_definition" "app" {
  family                   = "${var.name}-${var.environment}"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = jsonencode([{
    name      = "url-shortener"
    image     = local.app_image
    essential = true

    portMappings = [{
      containerPort = 8080
      protocol      = "tcp"
    }]

    environment = [
      { name = "SERVER_PORT", value = "8080" },
      { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
      {
        name  = "SPRING_DATASOURCE_URL"
        value = "jdbc:postgresql://${var.db_primary_endpoint}:5432/${var.db_name}"
      },
      {
        name  = "SPRING_DATASOURCE_READ_URL"
        value = "jdbc:postgresql://${var.db_replica_endpoint}:5432/${var.db_name}"
      },
      { name = "SPRING_DATA_REDIS_HOST", value = var.redis_endpoint },
      { name = "SPRING_DATA_REDIS_PORT", value = "6379" }
    ]

    # Credentials injected from Secrets Manager — never in plaintext env vars
    secrets = [
      { name = "SPRING_DATASOURCE_USERNAME", valueFrom = "${var.db_secret_arn}:username::" },
      { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = "${var.db_secret_arn}:password::" }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.app.name
        "awslogs-region"        = data.aws_region.current.name
        "awslogs-stream-prefix" = "ecs"
      }
    }

    healthCheck = {
      command     = ["CMD-SHELL", "curl -sf http://localhost:8080/actuator/health || exit 1"]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 60
    }
  }])

  tags = var.tags
}

# ── ECS Service ────────────────────────────────────────────────────────────────

resource "aws_ecs_service" "app" {
  name            = "${var.name}-${var.environment}"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [var.security_group_id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = var.target_group_arn
    container_name   = "url-shortener"
    container_port   = 8080
  }

  depends_on = [aws_iam_role_policy_attachment.execution_managed]

  tags = merge(var.tags, { Name = "${var.name}-${var.environment}-service" })

  lifecycle {
    # CD pipeline owns task_definition and desired_count after first deploy
    ignore_changes = [task_definition, desired_count]
  }
}

# ── Auto Scaling ───────────────────────────────────────────────────────────────

resource "aws_appautoscaling_target" "ecs" {
  max_capacity       = var.max_capacity
  min_capacity       = var.min_capacity
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.app.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "cpu" {
  name               = "${var.name}-${var.environment}-cpu-scaling"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.ecs.resource_id
  scalable_dimension = aws_appautoscaling_target.ecs.scalable_dimension
  service_namespace  = aws_appautoscaling_target.ecs.service_namespace

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
    target_value       = 70.0
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
  }
}
