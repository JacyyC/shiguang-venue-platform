-- P1 reservation credential remediation: legacy QR payloads contain complete short codes.
-- Keep the lifecycle evidence and short-code hash/last4 while removing the replayable plaintext value.
UPDATE `reservation_lifecycle`
SET `qr_payload` = NULL
WHERE `qr_payload` IS NOT NULL;
