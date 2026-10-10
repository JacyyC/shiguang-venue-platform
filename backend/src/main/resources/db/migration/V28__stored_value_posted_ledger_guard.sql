-- 账本交易先以 OPEN 写入分录，再通过唯一状态转换落为 POSTED。
-- 已 POSTED 交易仍不可更新；POSTED 必须有至少一条且借贷平衡的分录。

DROP TRIGGER `trg_stored_value_ledger_transaction_no_update`;

CREATE TRIGGER `trg_stored_value_ledger_transaction_no_insert_posted`
BEFORE INSERT ON `stored_value_ledger_transaction`
FOR EACH ROW
BEGIN
  IF NEW.status = 'POSTED' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'stored value ledger transactions must post after entries';
  END IF;
END;

CREATE TRIGGER `trg_stored_value_ledger_transaction_posted_guard`
BEFORE UPDATE ON `stored_value_ledger_transaction`
FOR EACH ROW
BEGIN
  DECLARE entry_count BIGINT DEFAULT 0;
  DECLARE debit_amount DECIMAL(18,2) DEFAULT 0.00;
  DECLARE credit_amount DECIMAL(18,2) DEFAULT 0.00;

  IF NOT (
    OLD.status = 'OPEN' AND NEW.status = 'POSTED'
    AND OLD.issuer_id <=> NEW.issuer_id
    AND OLD.product_id <=> NEW.product_id
    AND OLD.account_id <=> NEW.account_id
    AND OLD.currency <=> NEW.currency
    AND OLD.transaction_type <=> NEW.transaction_type
    AND OLD.source_type <=> NEW.source_type
    AND OLD.source_id <=> NEW.source_id
    AND OLD.business_key <=> NEW.business_key
    AND OLD.payload_hash <=> NEW.payload_hash
    AND OLD.policy_version <=> NEW.policy_version
    AND OLD.reason <=> NEW.reason
    AND OLD.operator_id <=> NEW.operator_id
    AND OLD.system_source <=> NEW.system_source
    AND OLD.occurred_at <=> NEW.occurred_at
    AND OLD.create_time <=> NEW.create_time
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'stored value ledger transactions are immutable';
  END IF;

  SELECT COUNT(*),
         COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE 0.00 END), 0.00),
         COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0.00 END), 0.00)
    INTO entry_count, debit_amount, credit_amount
    FROM stored_value_ledger_entry
   WHERE transaction_id = OLD.id;

  IF entry_count = 0 OR debit_amount <> credit_amount THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'posted stored value ledger transaction must have balanced entries';
  END IF;
END;
