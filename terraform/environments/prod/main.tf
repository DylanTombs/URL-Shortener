terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.0"
    }
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "url-shortener"
      Environment = "prod"
      ManagedBy   = "terraform"
    }
  }
}

locals {
  name        = "url-shortener"
  environment = "prod"
}

module "vpc" {
  source = "../../modules/vpc"

  name              = local.name
  environment       = local.environment
  vpc_cidr          = "10.1.0.0/16" # separate CIDR from dev to allow VPC peering if needed
  nat_gateway_count = 2              # one per AZ — NAT failure must not cause an outage
}

module "alb" {
  source = "../../modules/alb"

  name              = local.name
  environment       = local.environment
  vpc_id            = module.vpc.vpc_id
  public_subnet_ids = module.vpc.public_subnet_ids
  security_group_id = module.vpc.alb_security_group_id
  certificate_arn   = var.certificate_arn
}

module "rds" {
  source = "../../modules/rds"

  name                   = local.name
  environment            = local.environment
  private_subnet_ids     = module.vpc.private_subnet_ids
  security_group_id      = module.vpc.rds_security_group_id
  instance_class         = "db.t3.small"
  replica_instance_class = "db.t3.small"
  allocated_storage      = 50
  max_allocated_storage  = 500
  db_name                = "urlshortener"
  db_username            = "urlshortener_admin"
  multi_az               = true  # synchronous standby in second AZ
  create_read_replica    = true
}

module "elasticache" {
  source = "../../modules/elasticache"

  name               = local.name
  environment        = local.environment
  private_subnet_ids = module.vpc.private_subnet_ids
  security_group_id  = module.vpc.elasticache_security_group_id
  node_type          = "cache.t3.small"
  num_cache_clusters = 2 # primary + replica with automatic failover
}

module "ecs" {
  source = "../../modules/ecs"

  name               = local.name
  environment        = local.environment
  private_subnet_ids = module.vpc.private_subnet_ids
  security_group_id  = module.vpc.ecs_security_group_id
  target_group_arn   = module.alb.target_group_arn

  db_primary_endpoint = module.rds.primary_endpoint
  db_replica_endpoint = module.rds.replica_endpoint
  db_name             = module.rds.db_name
  db_secret_arn       = module.rds.secret_arn
  redis_endpoint      = module.elasticache.primary_endpoint
  app_base_url        = var.app_base_url

  task_cpu           = 1024
  task_memory        = 2048
  desired_count      = 2  # minimum 2 for HA across AZs
  min_capacity       = 2
  max_capacity       = 10
  log_retention_days = 30
}

module "waf" {
  source = "../../modules/waf"

  name        = local.name
  environment = local.environment
  alb_arn     = module.alb.alb_arn
}

module "github_oidc" {
  source = "../../modules/github-oidc"

  name               = local.name
  environment        = local.environment
  github_repo        = var.github_repo
  ecr_repository_arn = module.ecs.ecr_repository_arn
  ecs_role_arns      = module.ecs.iam_role_arns
}

module "cloudwatch" {
  source = "../../modules/cloudwatch"

  name        = local.name
  environment = local.environment

  alb_arn_suffix          = module.alb.alb_arn_suffix
  target_group_arn_suffix = module.alb.target_group_arn_suffix
  rds_instance_identifier = module.rds.primary_identifier
  ecs_cluster_name        = module.ecs.cluster_name
  ecs_service_name        = module.ecs.service_name
  log_group_name          = module.ecs.cloudwatch_log_group

  alarm_email = var.alarm_email
}
