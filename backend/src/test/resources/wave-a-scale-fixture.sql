CREATE TABLE wave_a_scale_digit (
  n int NOT NULL PRIMARY KEY
);

INSERT INTO wave_a_scale_digit (n) VALUES
  (0), (1), (2), (3), (4), (5), (6), (7), (8), (9);

CREATE TABLE wave_a_scale_sequence (
  n int NOT NULL PRIMARY KEY
);

INSERT INTO wave_a_scale_sequence (n)
SELECT d0.n + d1.n * 10 + d2.n * 100 + d3.n * 1000 + d4.n * 10000 + d5.n * 100000 + 1
FROM wave_a_scale_digit d0
CROSS JOIN wave_a_scale_digit d1
CROSS JOIN wave_a_scale_digit d2
CROSS JOIN wave_a_scale_digit d3
CROSS JOIN wave_a_scale_digit d4
CROSS JOIN wave_a_scale_digit d5
WHERE d0.n + d1.n * 10 + d2.n * 100 + d3.n * 1000 + d4.n * 10000 + d5.n * 100000 < 500000;

INSERT INTO sys_user (
  id, username, password, nickname, status, role_id, create_time, update_time, deleted
)
SELECT
  5000000 + n,
  CONCAT('scale_user_', LPAD(n, 5, '0')),
  '$2a$10$bBZklGMIFdu55euAGSmIdeHtjT6HlVmK7awTemoxDT3x4apR5lrwK',
  CONCAT('Scale User ', n),
  'ACTIVE', 1, DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 30 DAY),
  DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 30 DAY), 0
FROM wave_a_scale_sequence
WHERE n <= 19999;

INSERT INTO venue (
  id, name, address, area, open_time, close_time, status, create_time, update_time, deleted
)
SELECT
  1000000 + n,
  CONCAT('Scale Venue ', n),
  CONCAT('Scale Address ', n),
  CONCAT('AREA-', n),
  '08:00:00', '22:00:00', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM wave_a_scale_sequence
WHERE n <= 5;

INSERT INTO venue_space (
  id, venue_id, name, type, code, capacity, price_per_hour, status, create_time, update_time, deleted
)
SELECT
  2000000 + n,
  1000000 + FLOOR((n - 1) / 40) + 1,
  CONCAT('Scale Space ', n),
  CASE WHEN n <= 100 THEN 'STUDY_ROOM' ELSE 'ASSIGNED_SEAT' END,
  CONCAT('SCALE-', LPAD(n, 3, '0')),
  CASE WHEN n <= 100 THEN 100 ELSE 50 END,
  20.00, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM wave_a_scale_sequence
WHERE n <= 200;

INSERT INTO venue_resource_rule (
  id, space_id, resource_category, booking_mode, capacity_limit, version_no,
  status, active_flag, effective_from, reason_text, create_time, update_time
)
SELECT
  3000000 + n,
  2000000 + n,
  'STUDY_ROOM',
  CASE WHEN n <= 100 THEN 'CAPACITY' ELSE 'ASSIGNED_SEAT' END,
  CASE WHEN n <= 100 THEN 100 ELSE NULL END,
  1, 'ACTIVE', 1, CURRENT_TIMESTAMP, 'Wave A S08 scale fixture', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM wave_a_scale_sequence
WHERE n <= 200;

INSERT INTO venue_resource_seat (
  id, space_id, rule_id, seat_code, seat_label, zone_code, sort_order,
  status, create_time, update_time
)
SELECT
  4000000 + n,
  2000000 + 100 + FLOOR((n - 1) / 50) + 1,
  3000000 + 100 + FLOOR((n - 1) / 50) + 1,
  CONCAT('S', LPAD(MOD(n - 1, 50) + 1, 2, '0')),
  CONCAT('Seat ', MOD(n - 1, 50) + 1),
  CONCAT('Z', FLOOR(MOD(n - 1, 50) / 10) + 1),
  MOD(n - 1, 50) + 1,
  'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM wave_a_scale_sequence
WHERE n <= 5000;

INSERT INTO community_group (
  id, group_no, name, description, owner_user_id, visibility, join_policy, status,
  idempotency_key, request_hash, version_no, create_time, update_time
)
SELECT
  6000000 + n,
  CONCAT('CG-SCALE-', LPAD(n, 4, '0')),
  CONCAT('scale-group-', LPAD(n, 4, '0')),
  'Wave A S08 scale group',
  5000000 + n,
  'PLATFORM_VISIBLE', 'DIRECT', 'ACTIVE',
  CONCAT('scale-group-', n), CONCAT('scale-group-hash-', n), 0,
  DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 14 DAY), DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 14 DAY)
FROM wave_a_scale_sequence
WHERE n <= 500;

INSERT INTO community_group_member (
  id, group_id, user_id, member_role, status, last_idempotency_key, last_request_hash,
  joined_at, create_time, update_time
)
SELECT
  6100000 + n,
  6000000 + n,
  5000000 + n,
  'OWNER', 'ACTIVE', CONCAT('scale-owner-', n), CONCAT('scale-owner-hash-', n),
  DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 14 DAY),
  DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 14 DAY), DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 14 DAY)
FROM wave_a_scale_sequence
WHERE n <= 500;

INSERT INTO community_post (
  id, post_no, group_id, author_user_id, title, content_text, status,
  idempotency_key, request_hash, version_no, published_at, create_time, update_time
)
SELECT
  7000000 + n,
  CONCAT('CP-SCALE-', LPAD(n, 6, '0')),
  6000000 + MOD(n - 1, 500) + 1,
  5000000 + MOD(n - 1, 19999) + 1,
  CONCAT('scale-post-', LPAD(n, 6, '0')),
  CONCAT('Wave A S08 scale post ', n),
  'PUBLISHED', CONCAT('scale-post-', n), CONCAT('scale-post-hash-', n), 0,
  CASE WHEN n > 97000 THEN CURRENT_TIMESTAMP ELSE DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 7 DAY) END,
  CASE WHEN n > 97000 THEN CURRENT_TIMESTAMP ELSE DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 7 DAY) END,
  CASE WHEN n > 97000 THEN CURRENT_TIMESTAMP ELSE DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 7 DAY) END
FROM wave_a_scale_sequence
WHERE n <= 100000;

INSERT INTO community_comment (
  id, comment_no, post_id, group_id, author_user_id, content_text, status,
  idempotency_key, request_hash, version_no, published_at, create_time, update_time
)
SELECT
  8000000 + n,
  CONCAT('CC-SCALE-', LPAD(n, 6, '0')),
  7000000 + MOD(n - 1, 100000) + 1,
  6000000 + MOD(n - 1, 500) + 1,
  5000000 + MOD(n - 1, 19999) + 1,
  CONCAT('Wave A S08 scale comment ', n),
  'PUBLISHED', CONCAT('scale-comment-', n), CONCAT('scale-comment-hash-', n), 0,
  DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 7 DAY),
  DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 7 DAY), DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 7 DAY)
FROM wave_a_scale_sequence
WHERE n <= 500000;

INSERT INTO community_activity (
  id, activity_no, group_id, organizer_user_id, activity_type, title, description,
  start_time, end_time, location_type, area_code, location_commitment_status,
  capacity_total, participant_count, status, version_no, published_at, create_time, update_time
)
SELECT
  9000000 + n,
  CONCAT('ACT-SCALE-', LPAD(n, 5, '0')),
  6000000 + MOD(n - 1, 500) + 1,
  CASE WHEN n <= 200 THEN 1 ELSE 5000000 + MOD(n - 1, 19999) + 1 END,
  'STUDY', CONCAT('scale-activity-', LPAD(n, 5, '0')), 'Wave A S08 scale activity',
  DATE_ADD(CURRENT_TIMESTAMP, INTERVAL MOD(n - 1, 30) + 1 DAY),
  DATE_ADD(DATE_ADD(CURRENT_TIMESTAMP, INTERVAL MOD(n - 1, 30) + 1 DAY), INTERVAL 2 HOUR),
  'CAMPUS_AREA', CONCAT('AREA-', MOD(n - 1, 5) + 1), 'UNCONFIRMED',
  10, 1, 'PUBLISHED', 1, DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 2 DAY),
  DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 2 DAY), DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 2 DAY)
FROM wave_a_scale_sequence
WHERE n <= 5000;

INSERT INTO community_activity_application (
  id, application_no, target_type, target_id, applicant_user_id, status,
  applied_at, version_no, create_time, update_time
)
SELECT
  9200000 + n,
  CONCAT('APP-SCALE-', LPAD(n, 3, '0')),
  'ACTIVITY', 9000000 + n, 5000000 + n, 'PENDING',
  DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 DAY), 0,
  DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 DAY), DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 DAY)
FROM wave_a_scale_sequence
WHERE n <= 200;

INSERT INTO community_companion_request (
  id, request_no, group_id, owner_user_id, activity_type, title, description,
  window_start, window_end, location_type, area_code, location_commitment_status,
  capacity_total, participant_count, status, version_no, published_at, create_time, update_time
)
SELECT
  9100000 + n,
  CONCAT('COMP-SCALE-', LPAD(n, 5, '0')),
  6000000 + MOD(n - 1, 500) + 1,
  5000000 + MOD(n - 1, 19999) + 1,
  'STUDY', CONCAT('scale-companion-', LPAD(n, 5, '0')), 'Wave A S08 scale companion request',
  DATE_ADD(CURRENT_TIMESTAMP, INTERVAL MOD(n - 1, 30) + 1 DAY),
  DATE_ADD(DATE_ADD(CURRENT_TIMESTAMP, INTERVAL MOD(n - 1, 30) + 1 DAY), INTERVAL 2 HOUR),
  'CAMPUS_AREA', CONCAT('AREA-', MOD(n - 1, 5) + 1), 'UNCONFIRMED',
  4, 1, 'PUBLISHED', 1, DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 2 DAY),
  DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 2 DAY), DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 2 DAY)
FROM wave_a_scale_sequence
WHERE n <= 5000;

INSERT INTO reservation (
  id, user_id, venue_id, space_id, start_time, end_time, status,
  contact_name, contact_phone, amount, original_amount, payable_amount,
  resource_category_snapshot, booking_mode_snapshot, rule_id_snapshot,
  rule_version_snapshot, participant_count, allocation_snapshot_json,
  create_time, update_time, deleted
)
SELECT
  10000000 + n,
  5000000 + MOD(n - 1, 19999) + 1,
  1000000 + FLOOR(MOD(n - 1, 100) / 40) + 1,
  2000000 + MOD(n - 1, 100) + 1,
  TIMESTAMP(DATE_ADD(CURRENT_DATE, INTERVAL 30 DAY), '09:00:00'),
  TIMESTAMP(DATE_ADD(CURRENT_DATE, INTERVAL 30 DAY), '10:00:00'),
  'PENDING_REVIEW', 'Scale User', '13800000000', 20.00, 20.00, 20.00,
  'STUDY_ROOM', 'CAPACITY', 3000000 + MOD(n - 1, 100) + 1,
  1, 1, JSON_OBJECT('source', 'WAVE_A_S08', 'bookingMode', 'CAPACITY', 'participantCount', 1),
  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM wave_a_scale_sequence
WHERE n <= 2000;

INSERT INTO reservation_occupancy (
  id, reservation_id, space_id, slot_date, start_time, end_time, active_flag,
  rule_id, resource_category_snapshot, booking_mode, rule_version_snapshot,
  quantity, allocation_snapshot_json
)
SELECT
  10100000 + n,
  10000000 + n,
  2000000 + MOD(n - 1, 100) + 1,
  DATE_ADD(CURRENT_DATE, INTERVAL 30 DAY), '09:00:00', '10:00:00', 1,
  3000000 + MOD(n - 1, 100) + 1,
  'STUDY_ROOM', 'CAPACITY', 1, 1,
  JSON_OBJECT('source', 'WAVE_A_S08', 'bookingMode', 'CAPACITY', 'participantCount', 1)
FROM wave_a_scale_sequence
WHERE n <= 2000;

ANALYZE TABLE sys_user, venue, venue_space, venue_resource_rule, venue_resource_seat;
ANALYZE TABLE community_group, community_group_member, community_post, community_comment;
ANALYZE TABLE community_activity, community_companion_request, community_activity_application;
ANALYZE TABLE reservation, reservation_occupancy;

DROP TABLE wave_a_scale_sequence;
DROP TABLE wave_a_scale_digit;
