-- P1 identity remediation: invalidate pre-change JWTs and make user mutations compare-and-set.
ALTER TABLE `sys_user`
  ADD COLUMN `credential_version` bigint NOT NULL DEFAULT 0 AFTER `password`,
  ADD COLUMN `user_version` bigint NOT NULL DEFAULT 0 AFTER `credential_version`;
