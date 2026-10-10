ALTER TABLE reservation
  ADD INDEX idx_reservation_venue_create (venue_id, create_time),
  ADD INDEX idx_reservation_create (create_time);

ALTER TABLE payment_record
  ADD INDEX idx_payment_status_time_order (status, pay_time, order_id);

ALTER TABLE refund_record
  ADD INDEX idx_refund_status_time_order (status, update_time, order_id);
