resource "aws_wafv2_web_acl" "main" {
  name  = "${var.name}-${var.environment}-waf"
  scope = "REGIONAL" # ALB-attached WAF must be REGIONAL (not CLOUDFRONT)

  default_action {
    allow {}
  }

  # Rule 1: Rate limit per IP — first line of defence against URL enumeration.
  # 100 requests / 5-minute window is the minimum AWS WAF supports and matches
  # the spec. Any IP exceeding this gets a 403 until the window resets.
  rule {
    name     = "RateLimitPerIP"
    priority = 1

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = 100
        aggregate_key_type = "IP"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-${var.environment}-rate-limit"
      sampled_requests_enabled   = true
    }
  }

  # Rule 2: AWS Managed Rules — Common Rule Set (SQLi, XSS, bad inputs, etc.)
  rule {
    name     = "AWSManagedRulesCommonRuleSet"
    priority = 2

    override_action {
      none {} # use the rule group's own actions (block/allow per rule)
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesCommonRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-${var.environment}-common-rules"
      sampled_requests_enabled   = true
    }
  }

  # Rule 3: AWS Managed Rules — Known Bad Inputs
  rule {
    name     = "AWSManagedRulesKnownBadInputsRuleSet"
    priority = 3

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-${var.environment}-bad-inputs"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${var.name}-${var.environment}-waf"
    sampled_requests_enabled   = true
  }

  tags = merge(var.tags, { Name = "${var.name}-${var.environment}-waf" })
}

resource "aws_wafv2_web_acl_association" "main" {
  resource_arn = var.alb_arn
  web_acl_arn  = aws_wafv2_web_acl.main.arn
}
