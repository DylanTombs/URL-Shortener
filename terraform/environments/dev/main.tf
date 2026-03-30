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
      Environment = "dev"
      ManagedBy   = "terraform"
    }
  }
}

locals {
  name        = "url-shortener"
  environment = "dev"
}

module "vpc" {
  source = "../../modules/vpc"

  name              = local.name
  environment       = local.environment
  vpc_cidr          = "10.0.0.0/16"
  nat_gateway_count = 1 # single NAT for dev — HA not needed, saves ~$35/month
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
  instance_class         = "db.t3.micro"
  replica_instance_class = "db.t3.micro"
  allocated_storage      = 20
  max_allocated_storage  = 100
  db_name                = "urlshortener"
  db_username            = "urlshortener_admin"
  multi_az               = false
  create_read_replica    = true
}

module "elasticache" {
  source = "../../modules/elasticache"

  name               = local.name
  environment        = local.environment
  private_subnet_ids = module.vpc.private_subnet_ids
  security_group_id  = module.vpc.elasticache_security_group_id
  node_type          = "cache.t3.micro"
  num_cache_clusters = 1 # single node for dev
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

  task_cpu           = 256
  task_memory        = 512
  desired_count      = 1
  min_capacity       = 1
  max_capacity       = 4
  log_retention_days = 7
}

module "waf" {
  source = "../../modules/waf"

  name        = local.name
  environment = local.environment
  alb_arn     = module.alb.alb_arn
}
