variable "project_id" {
  type = string
}

variable "region" {
  type    = string
  default = "us-central1"
}

variable "cloud_run_service" {
  type    = string
  default = "mobile-agent-api"
}

variable "cloud_run_image" {
  type = string
}

variable "guide_media_bucket" {
  type = string
}

variable "sql_instance_name" {
  type    = string
  default = "mobile-agent-pg"
}

variable "sql_database_name" {
  type    = string
  default = "mobile_agent"
}

variable "sql_user" {
  type    = string
  default = "mobile_agent"
}

variable "sql_password" {
  type      = string
  sensitive = true
}

variable "sql_tier" {
  type    = string
  default = "db-custom-1-3840"
}

variable "cloud_tasks_queue" {
  type    = string
  default = "session-recap-video"
}

variable "postgres_url_secret" {
  type      = string
  sensitive = true
}

variable "internal_job_token" {
  type      = string
  sensitive = true
}
