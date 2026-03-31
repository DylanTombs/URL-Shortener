output "role_arn" {
  description = "ARN of the IAM role assumed by GitHub Actions — set as AWS_ROLE_ARN_DEV or AWS_ROLE_ARN_PROD secret"
  value       = aws_iam_role.github_actions.arn
}

output "oidc_provider_arn" {
  description = "ARN of the GitHub Actions OIDC provider"
  value       = aws_iam_openid_connect_provider.github.arn
}
