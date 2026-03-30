data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, 2)
}

# ── VPC ───────────────────────────────────────────────────────────────────────

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = merge(var.tags, { Name = "${var.name}-${var.environment}-vpc" })
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = merge(var.tags, { Name = "${var.name}-${var.environment}-igw" })
}

# ── Subnets ───────────────────────────────────────────────────────────────────

# Public subnets — ALB only
resource "aws_subnet" "public" {
  count                   = 2
  vpc_id                  = aws_vpc.main.id
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, count.index + 1)
  availability_zone       = local.azs[count.index]
  map_public_ip_on_launch = true
  tags = merge(var.tags, {
    Name = "${var.name}-${var.environment}-public-${count.index + 1}"
    Tier = "public"
  })
}

# Private subnets — ECS tasks, RDS, ElastiCache
resource "aws_subnet" "private" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index + 10)
  availability_zone = local.azs[count.index]
  tags = merge(var.tags, {
    Name = "${var.name}-${var.environment}-private-${count.index + 1}"
    Tier = "private"
  })
}

# ── NAT Gateway ───────────────────────────────────────────────────────────────
# Dev: single NAT (cost saving). Prod: one per AZ (HA).

resource "aws_eip" "nat" {
  count  = var.nat_gateway_count
  domain = "vpc"
  tags   = merge(var.tags, { Name = "${var.name}-${var.environment}-nat-eip-${count.index + 1}" })
}

resource "aws_nat_gateway" "main" {
  count         = var.nat_gateway_count
  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id
  depends_on    = [aws_internet_gateway.main]
  tags          = merge(var.tags, { Name = "${var.name}-${var.environment}-nat-${count.index + 1}" })
}

# ── Route Tables ──────────────────────────────────────────────────────────────

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }
  tags = merge(var.tags, { Name = "${var.name}-${var.environment}-public-rt" })
}

# One private route table per NAT gateway. When nat_gateway_count=1, both
# private subnets share the single NAT (min() clamps the index).
resource "aws_route_table" "private" {
  count  = var.nat_gateway_count
  vpc_id = aws_vpc.main.id
  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.main[count.index].id
  }
  tags = merge(var.tags, { Name = "${var.name}-${var.environment}-private-rt-${count.index + 1}" })
}

resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "private" {
  count          = 2
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private[min(count.index, var.nat_gateway_count - 1)].id
}

# ── Security Groups ───────────────────────────────────────────────────────────
# All SGs live here so modules can reference them without circular deps.

resource "aws_security_group" "alb" {
  name        = "${var.name}-${var.environment}-alb-sg"
  description = "ALB: allow HTTP/HTTPS from internet"
  vpc_id      = aws_vpc.main.id

  ingress {
    description = "HTTP"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "${var.name}-${var.environment}-alb-sg" })
}

resource "aws_security_group" "ecs" {
  name        = "${var.name}-${var.environment}-ecs-sg"
  description = "ECS tasks: inbound from ALB on 8080, all outbound (NAT handles egress)"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "App port from ALB"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "${var.name}-${var.environment}-ecs-sg" })
}

resource "aws_security_group" "rds" {
  name        = "${var.name}-${var.environment}-rds-sg"
  description = "RDS: Postgres 5432 from ECS tasks only"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "Postgres from ECS"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "${var.name}-${var.environment}-rds-sg" })
}

resource "aws_security_group" "elasticache" {
  name        = "${var.name}-${var.environment}-elasticache-sg"
  description = "ElastiCache: Redis 6379 from ECS tasks only"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "Redis from ECS"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "${var.name}-${var.environment}-elasticache-sg" })
}
