terraform {
  required_version = ">= 1.5.0"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.0"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

resource "google_storage_bucket" "guide_media" {
  name                        = var.guide_media_bucket
  location                    = var.region
  uniform_bucket_level_access = true
  force_destroy               = false

  lifecycle_rule {
    condition {
      age = 7
    }
    action {
      type = "Delete"
    }
  }
}

resource "google_sql_database_instance" "mobile_agent" {
  name             = var.sql_instance_name
  region           = var.region
  database_version = "POSTGRES_15"

  settings {
    tier = var.sql_tier
    ip_configuration {
      ipv4_enabled = false
    }
  }
}

resource "google_sql_database" "mobile_agent" {
  name     = var.sql_database_name
  instance = google_sql_database_instance.mobile_agent.name
}

resource "google_sql_user" "mobile_agent" {
  name     = var.sql_user
  instance = google_sql_database_instance.mobile_agent.name
  password = var.sql_password
}

resource "google_cloud_tasks_queue" "session_recap" {
  name     = var.cloud_tasks_queue
  location = var.region
}

resource "google_secret_manager_secret" "postgres_url" {
  secret_id = "POSTGRES_URL"
  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "postgres_url" {
  secret      = google_secret_manager_secret.postgres_url.id
  secret_data = var.postgres_url_secret
}

resource "google_secret_manager_secret" "internal_job_token" {
  secret_id = "INTERNAL_JOB_TOKEN"
  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "internal_job_token" {
  secret      = google_secret_manager_secret.internal_job_token.id
  secret_data = var.internal_job_token
}

resource "google_cloud_run_v2_service" "mobile_agent" {
  name     = var.cloud_run_service
  location = var.region
  ingress  = "INGRESS_TRAFFIC_ALL"

  template {
    scaling {
      min_instance_count = 1
      max_instance_count = 20
    }
    containers {
      image = var.cloud_run_image
      env {
        name  = "GOOGLE_GENAI_USE_VERTEXAI"
        value = "true"
      }
      env {
        name  = "GOOGLE_CLOUD_PROJECT"
        value = var.project_id
      }
      env {
        name  = "GOOGLE_CLOUD_LOCATION"
        value = var.region
      }
      env {
        name  = "GUIDE_MEDIA_BUCKET"
        value = google_storage_bucket.guide_media.name
      }
      env {
        name  = "CLOUD_TASKS_QUEUE"
        value = google_cloud_tasks_queue.session_recap.name
      }
      env {
        name  = "CLOUD_TASKS_LOCATION"
        value = var.region
      }
      env {
        name  = "CLOUD_TASKS_PROJECT"
        value = var.project_id
      }
      env {
        name = "POSTGRES_URL"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.postgres_url.secret_id
            version = "latest"
          }
        }
      }
      env {
        name = "INTERNAL_JOB_TOKEN"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.internal_job_token.secret_id
            version = "latest"
          }
        }
      }
    }
  }
}

# ============================================================================
# IAM 强鉴权配置 - P0 安全防线
# ============================================================================
# 已移除 allUsers 公网裸奔配置！
# Cloud Run 现在要求所有请求必须携带有效的 IAM 身份凭证。
#
# Android 端接入方式：
#   1. Firebase App Check + ID Token
#   2. 或使用 Service Account 签发的 JWT
#
# 授权特定 Service Account 调用（按需取消注释）:
# resource "google_cloud_run_v2_service_iam_member" "mobile_client_invoker" {
#   name     = google_cloud_run_v2_service.mobile_agent.name
#   location = google_cloud_run_v2_service.mobile_agent.location
#   role     = "roles/run.invoker"
#   member   = "serviceAccount:${var.mobile_client_sa_email}"
# }

# 授权 Firebase App Check 验证后的用户（推荐生产方案）:
# resource "google_cloud_run_v2_service_iam_member" "firebase_app_check_invoker" {
#   name     = google_cloud_run_v2_service.mobile_agent.name
#   location = google_cloud_run_v2_service.mobile_agent.location
#   role     = "roles/run.invoker"
#   member   = "serviceAccount:firebase-app-check@${var.project_id}.iam.gserviceaccount.com"
# }
