terraform {
  backend "s3" {
    bucket         = "urlshortener-tfstate-810890577576"
    key            = "prod/terraform.tfstate"
    region         = "us-east-1"
    dynamodb_table = "urlshortener-tfstate-lock"
    encrypt        = true
  }
}
