output "cloud_run_url" {
  value = google_cloud_run_v2_service.mobile_agent.uri
}

output "guide_media_bucket" {
  value = google_storage_bucket.guide_media.name
}

output "screenshot_upload_bucket" {
  value = google_storage_bucket.screenshot_uploads.name
}

output "sql_connection_name" {
  description = "Cloud SQL connection name; use it in POSTGRES_URL as ?host=/cloudsql/<connection_name>."
  value       = google_sql_database_instance.mobile_agent.connection_name
}

output "cloud_tasks_queue" {
  value = google_cloud_tasks_queue.session_recap.name
}
