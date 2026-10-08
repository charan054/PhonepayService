-- Baseline of the PhonepayService schema as the JPA entities define it on 2026-10-08.
-- Every statement is IF NOT EXISTS, so it is a no-op on a database that Hibernate (ddl-auto=update)
-- already built, and builds the whole schema on a brand-new one. All services share one MySQL
-- schema, so each keeps its own history table (see spring.flyway.table).

CREATE TABLE IF NOT EXISTS `money_request` (
  `amount` decimal(19,2) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `payer_phno` bigint DEFAULT NULL,
  `requester_phno` bigint DEFAULT NULL,
  `resolved_at` datetime(6) DEFAULT NULL,
  `resulting_transaction_id` bigint DEFAULT NULL,
  `note` varchar(140) DEFAULT NULL,
  `status` enum('APPROVED','DECLINED','PENDING') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_money_request_requester_phno` (`requester_phno`),
  KEY `idx_money_request_payer_phno` (`payer_phno`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `recurring_payment` (
  `amount` decimal(19,2) DEFAULT NULL,
  `interval_days` int NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `last_run_at` datetime(6) DEFAULT NULL,
  `next_run_at` datetime(6) DEFAULT NULL,
  `owner_phno` bigint DEFAULT NULL,
  `payee_phno` bigint DEFAULT NULL,
  `note` varchar(140) DEFAULT NULL,
  `status` enum('ACTIVE','CANCELLED','PAUSED') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_recurring_payment_owner_phno` (`owner_phno`),
  KEY `idx_recurring_payment_status_next_run` (`status`,`next_run_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `saved_payee` (
  `created_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `owner_phno` bigint DEFAULT NULL,
  `payee_phno` bigint DEFAULT NULL,
  `nickname` varchar(50) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_saved_payee_owner_payee` (`owner_phno`,`payee_phno`),
  KEY `idx_saved_payee_owner_phno` (`owner_phno`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `transaction` (
  `amount` decimal(19,2) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `phno` bigint NOT NULL,
  `recieverno` bigint DEFAULT NULL,
  `refund_of_transaction_id` bigint DEFAULT NULL,
  `transaction_id` bigint DEFAULT NULL,
  `note` varchar(140) DEFAULT NULL,
  `failure_reason` varchar(255) DEFAULT NULL,
  `idempotency_key` varchar(255) DEFAULT NULL,
  `mode` varchar(255) DEFAULT NULL,
  `status` enum('COMPLETED','FAILED','NEEDS_RECONCILIATION','PENDING') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_transaction_phno_idempotency_key` (`phno`,`idempotency_key`),
  UNIQUE KEY `UKnevcwmpu8hb3a1naph2fyvqyu` (`transaction_id`),
  KEY `idx_transaction_phno` (`phno`),
  KEY `idx_transaction_recieverno` (`recieverno`),
  KEY `idx_transaction_refund_of` (`refund_of_transaction_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `upi_collect_request` (
  `amount` decimal(19,2) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `payer_phno` bigint DEFAULT NULL,
  `resolved_at` datetime(6) DEFAULT NULL,
  `result_transaction_id` bigint DEFAULT NULL,
  `merchant_reference` varchar(100) DEFAULT NULL,
  `note` varchar(140) DEFAULT NULL,
  `status` varchar(20) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_upi_collect_request_merchant_reference` (`merchant_reference`),
  KEY `idx_upi_collect_request_payer_phno` (`payer_phno`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `user_session` (
  `created_at` datetime(6) DEFAULT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `phno` bigint NOT NULL,
  `token_hash` varchar(64) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKau1tr2kouh0rhp1us6nqjpmyi` (`token_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
