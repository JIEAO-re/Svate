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

# Screenshot upload bucket consumed by the mobile-agent API
# (SCREENSHOT_UPLOAD_BUCKET env, see src/lib/mobile-agent/env.ts). This used
# to be a free-floating literal in cloudbuild.yaml (with a "stave-" typo);
# Terraform now owns both the bucket and the env injection.
resource "google_storage_bucket" "screenshot_uploads" {
  name                        = var.screenshot_upload_bucket
  location                    = var.region
  uniform_bucket_level_access = true
  force_destroy               = false

  # Screenshots are transient inputs for guide generation; expire them
  # automatically instead of accumulating storage cost.
  lifecycle_rule {
    condition {
      age = var.screenshot_upload_retention_days
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
      # Public IP enabled but with no authorized networks: the instance is
      # only reachable through the Cloud SQL connector, which Cloud Run
      # mounts as a unix socket (see the cloud_sql_instance volume below).
      # Note: ipv4_enabled = false without a private_network is rejected by
      # the provider, and private IP would require a VPC connector.
      ipv4_enabled = true
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

# SECURITY NOTE: secret values supplied through Terraform variables
# (var.postgres_url_secret, var.internal_job_token, var.sql_password) are
# persisted in PLAIN TEXT inside the Terraform state file. Use a remote state
# backend with encryption at rest and tight access control (e.g. a GCS backend
# with CMEK and IAM restricted to the deploy identity), and never commit local
# terraform.tfstate files to version control.
resource "google_secret_manager_secret" "postgres_url" {
  secret_id = "POSTGRES_URL"
  replication {
    auto {}
  }
}

# The connection string must use the Cloud SQL unix socket exposed by the
# cloud_sql_instance volume on the Cloud Run service, e.g.:
#   postgresql://USER:PASSWORD@/DBNAME?host=/cloudsql/PROJECT:REGION:INSTANCE
# (the host segment is the instance connection_name; see
# google_sql_database_instance.mobile_agent.connection_name).
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

    # Attach the Cloud SQL instance through the managed connector. Cloud Run
    # exposes it as a unix socket under /cloudsql/<connection_name>, which is
    # how the app must reach Postgres (the instance has no authorized
    # networks on its public IP).
    volumes {
      name = "cloudsql"
      cloud_sql_instance {
        instances = [google_sql_database_instance.mobile_agent.connection_name]
      }
    }

    containers {
      image = var.cloud_run_image

      volume_mounts {
        name       = "cloudsql"
        mount_path = "/cloudsql"
      }

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
        name  = "SCREENSHOT_UPLOAD_BUCKET"
        value = google_storage_bucket.screenshot_uploads.name
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
      # The secret value (DATABASE_URL-style connection string) must point at
      # the unix socket mounted above, i.e. use the
      # "?host=/cloudsql/<connection_name>" form instead of a TCP host:port.
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
# Invoker IAM policy
# ============================================================================
# By default the service requires IAM-authenticated invocations: no allUsers
# binding exists, so unauthenticated requests get a 403. Android clients are
# expected to present valid IAM credentials (Firebase App Check + ID token, or
# a service-account-signed JWT).
#
# Demo/staging escape hatch: the service also ships web demo pages that are
# unreachable without a public binding. Set var.allow_unauthenticated = true
# to grant roles/run.invoker to allUsers for those environments. Keep it at
# the default (false) for production.
resource "google_cloud_run_v2_service_iam_member" "public_invoker" {
  count    = var.allow_unauthenticated ? 1 : 0
  name     = google_cloud_run_v2_service.mobile_agent.name
  location = google_cloud_run_v2_service.mobile_agent.location
  role     = "roles/run.invoker"
  member   = "allUsers"
}

# Grant a specific service account instead (uncomment as needed):
# resource "google_cloud_run_v2_service_iam_member" "mobile_client_invoker" {
#   name     = google_cloud_run_v2_service.mobile_agent.name
#   location = google_cloud_run_v2_service.mobile_agent.location
#   role     = "roles/run.invoker"
#   member   = "serviceAccount:${var.mobile_client_sa_email}"
# }

# Grant Firebase App Check verified callers (recommended for production):
# resource "google_cloud_run_v2_service_iam_member" "firebase_app_check_invoker" {
#   name     = google_cloud_run_v2_service.mobile_agent.name
#   location = google_cloud_run_v2_service.mobile_agent.location
#   role     = "roles/run.invoker"
#   member   = "serviceAccount:firebase-app-check@${var.project_id}.iam.gserviceaccount.com"
# }
