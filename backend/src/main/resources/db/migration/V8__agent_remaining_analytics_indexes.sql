ALTER TABLE funnel_event
  ADD INDEX idx_funnel_metric_scope_time (
    event_name, result_status, occurred_at, venue_id, session_key
  ),
  ADD INDEX idx_funnel_dropoff_scope_time (
    dropoff_reason_code, occurred_at, venue_id, session_key
  );

ALTER TABLE payment_record
  ADD INDEX idx_payment_failure_status_time_order (
    failure_code, status, pay_time, order_id
  );

ALTER TABLE business_event
  ADD INDEX idx_business_event_type_result_time_entity (
    event_type, result_status, occurred_at, entity_id, related_entity_id
  );

ALTER TABLE coupon_template
  ADD INDEX idx_coupon_template_scope_valid_time (
    venue_id, status, valid_start_time, valid_end_time
  );

ALTER TABLE user_coupon
  ADD INDEX idx_user_coupon_used_attribution (
    used_at, deleted, reservation_id, order_id, template_id
  );
