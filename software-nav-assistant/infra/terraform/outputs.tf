output "cloud_run_url" {
  value = google_cloud_run_v2_service.mobile_agent.uri
}

output "guide_media_bucket" {
  value = google_storage_bucket.guide_media.name
}

output "cloud_tasks_queue" {
  value = google_cloud_tasks_queue.session_recap.name
}
