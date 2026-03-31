# ── GitHub Actions OIDC Federation ────────────────────────────────────────────
# Allows GitHub Actions workflows to assume an IAM role without storing long-lived
# AWS access keys in GitHub Secrets. The OIDC provider validates the JWT issued
# by GitHub and the Condition block pins trust to this specific repository.

resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]

  # SHA-1 thumbprint of the GitHub OIDC intermediate CA certificate.
  # This value is stable; AWS validates it when the provider issues tokens.
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]

  tags = var.tags
}

# ── IAM Role assumed by GitHub Actions ────────────────────────────────────────

resource "aws_iam_role" "github_actions" {
  name = "${var.name}-github-actions-${var.environment}"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        }
        # StringLike allows both branch pushes and PR runs from this repo.
        # Replace with StringEquals + full ref to lock down to main-only.
        StringLike = {
          "token.actions.githubusercontent.com:sub" = "repo:${var.github_repo}:*"
        }
      }
    }]
  })

  tags = var.tags
}

# ── ECR permissions — push images ─────────────────────────────────────────────

resource "aws_iam_role_policy" "ecr" {
  name = "${var.name}-github-actions-${var.environment}-ecr"
  role = aws_iam_role.github_actions.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "ecr:GetAuthorizationToken"
      ]
      Resource = "*"
    }, {
      Effect = "Allow"
      Action = [
        "ecr:BatchCheckLayerAvailability",
        "ecr:PutImage",
        "ecr:InitiateLayerUpload",
        "ecr:UploadLayerPart",
        "ecr:CompleteLayerUpload",
        "ecr:BatchGetImage"
      ]
      Resource = var.ecr_repository_arn
    }]
  })
}

# ── ECS permissions — rolling deploy ──────────────────────────────────────────

resource "aws_iam_role_policy" "ecs_deploy" {
  name = "${var.name}-github-actions-${var.environment}-ecs"
  role = aws_iam_role.github_actions.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "ecs:RegisterTaskDefinition",
        "ecs:UpdateService",
        "ecs:DescribeServices",
        "ecs:DescribeTaskDefinition",
        "ecs:ListTaskDefinitions"
      ]
      Resource = "*"
    }, {
      # PassRole is required for ECS to accept a new task definition revision
      # that references the execution and task IAM roles.
      Effect   = "Allow"
      Action   = ["iam:PassRole"]
      Resource = var.ecs_role_arns
    }]
  })
}
