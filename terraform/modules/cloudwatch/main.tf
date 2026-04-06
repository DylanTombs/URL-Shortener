locals {
  prefix = "${var.name}-${var.environment}"
}

# ---------------------------------------------------------------------------
# SNS topic — alarm notifications
# ---------------------------------------------------------------------------

resource "aws_sns_topic" "alarms" {
  name = "${local.prefix}-alarms"
}

resource "aws_sns_topic_subscription" "email" {
  count     = var.alarm_email != "" ? 1 : 0
  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

# ---------------------------------------------------------------------------
# Alarm 1 — p99 redirect latency > 500 ms
#
# The url.redirect.percentile metric is emitted by Micrometer's CloudWatch
# exporter as a custom metric in the "UrlShortener" namespace.
# Threshold: 500 ms (0.5 seconds) — SLA for the redirect path.
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "redirect_p99_latency" {
  alarm_name          = "${local.prefix}-redirect-p99-latency"
  alarm_description   = "p99 redirect latency exceeded 500 ms"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  threshold           = 0.5 # seconds — Micrometer exports Timer in seconds
  treat_missing_data  = "notBreaching"

  metric_name = "url.redirect.percentile"
  namespace   = "UrlShortener"
  statistic   = "Maximum"
  period      = 60
  dimensions = {
    environment = var.environment
    phi         = "0.99"
  }

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]
}

# ---------------------------------------------------------------------------
# Alarm 2 — cache hit rate < 70 %
#
# Derived from the two counters Micrometer emits for url.redirect:
#   cache_hit=true  (hits)   and  cache_hit=false  (misses)
#
# CloudWatch metric math:
#   hit_rate = IF(total > 0, hits / total, 1)
# The IF guard prevents false alarms during periods of zero traffic.
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "cache_hit_rate" {
  alarm_name          = "${local.prefix}-cache-hit-rate-low"
  alarm_description   = "Cache hit rate dropped below 70 % (sustained 5 minutes)"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 5
  threshold           = 70
  treat_missing_data  = "notBreaching"

  metric_query {
    id          = "hits"
    return_data = false
    metric {
      metric_name = "url.redirect.count"
      namespace   = "UrlShortener"
      stat        = "Sum"
      period      = 60
      dimensions = {
        environment = var.environment
        cache_hit   = "true"
      }
    }
  }

  metric_query {
    id          = "misses"
    return_data = false
    metric {
      metric_name = "url.redirect.count"
      namespace   = "UrlShortener"
      stat        = "Sum"
      period      = 60
      dimensions = {
        environment = var.environment
        cache_hit   = "false"
      }
    }
  }

  metric_query {
    id          = "total"
    expression  = "hits + misses"
    return_data = false
  }

  metric_query {
    id          = "hit_rate"
    expression  = "IF(total > 0, (hits / total) * 100, 100)"
    label       = "Cache Hit Rate %"
    return_data = true
  }

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]
}

# ---------------------------------------------------------------------------
# Alarm 3 — RDS primary CPU > 80 %
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  alarm_name          = "${local.prefix}-rds-cpu-high"
  alarm_description   = "RDS primary CPU exceeded 80 % for 10 consecutive minutes"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 10
  threshold           = 80
  treat_missing_data  = "missing"

  metric_name = "CPUUtilization"
  namespace   = "AWS/RDS"
  statistic   = "Average"
  period      = 60
  dimensions = {
    DBInstanceIdentifier = var.rds_instance_identifier
  }

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]
}

# ---------------------------------------------------------------------------
# Alarm 4 — ALB 5xx error rate > 1 %
#
# Metric math: 5xx / (2xx + 3xx + 4xx + 5xx).
# Avoids false positives during low-traffic periods with IF(total > 0, ...).
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "alb_5xx_rate" {
  alarm_name          = "${local.prefix}-alb-5xx-rate"
  alarm_description   = "ALB 5xx error rate exceeded 1 % for 5 consecutive minutes"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 5
  threshold           = 1
  treat_missing_data  = "notBreaching"

  metric_query {
    id          = "e5xx"
    return_data = false
    metric {
      metric_name = "HTTPCode_Target_5XX_Count"
      namespace   = "AWS/ApplicationELB"
      stat        = "Sum"
      period      = 60
      dimensions = {
        LoadBalancer = var.alb_arn_suffix
        TargetGroup  = var.target_group_arn_suffix
      }
    }
  }

  metric_query {
    id          = "requests"
    return_data = false
    metric {
      metric_name = "RequestCount"
      namespace   = "AWS/ApplicationELB"
      stat        = "Sum"
      period      = 60
      dimensions = {
        LoadBalancer = var.alb_arn_suffix
        TargetGroup  = var.target_group_arn_suffix
      }
    }
  }

  metric_query {
    id          = "error_rate"
    expression  = "IF(requests > 0, (e5xx / requests) * 100, 0)"
    label       = "5xx Error Rate %"
    return_data = true
  }

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]
}

# ---------------------------------------------------------------------------
# CloudWatch Dashboard — 6 widgets
#
# Layout (24-column grid, each row 6 units tall):
#   Row 1 (y=0):  Redirect latency percentiles (0..12) | Cache hit rate (12..24)
#   Row 2 (y=6):  url.created counter (0..8)  | url.not_found (8..16) | url.expired (16..24)
#   Row 3 (y=12): RDS CPU (0..12)             | ECS CPU (12..24)
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_dashboard" "main" {
  dashboard_name = "${local.prefix}-overview"

  dashboard_body = jsonencode({
    widgets = [

      # Widget 1 — Redirect latency (p50 / p95 / p99)
      {
        type   = "metric"
        x      = 0
        y      = 0
        width  = 12
        height = 6
        properties = {
          title  = "Redirect Latency (seconds)"
          view   = "timeSeries"
          region = "us-east-1"
          metrics = [
            ["UrlShortener", "url.redirect.percentile", "environment", var.environment, "phi", "0.5", { label = "p50", color = "#2ca02c" }],
            ["UrlShortener", "url.redirect.percentile", "environment", var.environment, "phi", "0.95", { label = "p95", color = "#ff7f0e" }],
            ["UrlShortener", "url.redirect.percentile", "environment", var.environment, "phi", "0.99", { label = "p99", color = "#d62728" }]
          ]
          annotations = {
            horizontal = [{ value = 0.5, label = "SLA 500 ms", color = "#d62728" }]
          }
          yAxis = { left = { min = 0 } }
          period = 60
        }
      },

      # Widget 2 — Cache hit rate
      {
        type   = "metric"
        x      = 12
        y      = 0
        width  = 12
        height = 6
        properties = {
          title  = "Cache Hit Rate (%)"
          view   = "timeSeries"
          region = "us-east-1"
          metrics = [
            [{ expression = "IF(hits+misses > 0, (hits/(hits+misses))*100, 100)", label = "Hit Rate %", id = "hit_rate", color = "#2ca02c" }],
            ["UrlShortener", "url.redirect.count", "environment", var.environment, "cache_hit", "true", { id = "hits", visible = false }],
            ["UrlShortener", "url.redirect.count", "environment", var.environment, "cache_hit", "false", { id = "misses", visible = false }]
          ]
          annotations = {
            horizontal = [{ value = 70, label = "Minimum 70 %", color = "#d62728" }]
          }
          yAxis = { left = { min = 0, max = 100 } }
          period = 60
        }
      },

      # Widget 3 — URL creation rate
      {
        type   = "metric"
        x      = 0
        y      = 6
        width  = 8
        height = 6
        properties = {
          title  = "URLs Created (per minute)"
          view   = "timeSeries"
          region = "us-east-1"
          metrics = [
            ["UrlShortener", "url.created", "environment", var.environment, { label = "Created", stat = "Sum", color = "#2ca02c" }]
          ]
          yAxis  = { left = { min = 0 } }
          period = 60
        }
      },

      # Widget 4 — Not-found errors
      {
        type   = "metric"
        x      = 8
        y      = 6
        width  = 8
        height = 6
        properties = {
          title  = "404 Not Found (per minute)"
          view   = "timeSeries"
          region = "us-east-1"
          metrics = [
            ["UrlShortener", "url.not_found", "environment", var.environment, { label = "Not Found", stat = "Sum", color = "#ff7f0e" }]
          ]
          yAxis  = { left = { min = 0 } }
          period = 60
        }
      },

      # Widget 5 — Expired URL hits
      {
        type   = "metric"
        x      = 16
        y      = 6
        width  = 8
        height = 6
        properties = {
          title  = "410 Expired (per minute)"
          view   = "timeSeries"
          region = "us-east-1"
          metrics = [
            ["UrlShortener", "url.expired", "environment", var.environment, { label = "Expired", stat = "Sum", color = "#9467bd" }]
          ]
          yAxis  = { left = { min = 0 } }
          period = 60
        }
      },

      # Widget 6 — RDS CPU
      {
        type   = "metric"
        x      = 0
        y      = 12
        width  = 12
        height = 6
        properties = {
          title  = "RDS CPU Utilization (%)"
          view   = "timeSeries"
          region = "us-east-1"
          metrics = [
            ["AWS/RDS", "CPUUtilization", "DBInstanceIdentifier", var.rds_instance_identifier, { label = "RDS CPU %", color = "#1f77b4" }]
          ]
          annotations = {
            horizontal = [{ value = 80, label = "Alarm 80 %", color = "#d62728" }]
          }
          yAxis  = { left = { min = 0, max = 100 } }
          period = 60
        }
      }
    ]
  })
}
