-- A customer's complaint about one of their completed payments, resolved by staff (DisputeService).
CREATE TABLE IF NOT EXISTS `payment_dispute` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `transaction_id` bigint NOT NULL,
  `phno` bigint NOT NULL,
  `reason` varchar(500) DEFAULT NULL,
  `status` varchar(10) NOT NULL,
  `resolution_note` varchar(500) DEFAULT NULL,
  `refund_transaction_id` bigint DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `resolved_at` datetime(6) DEFAULT NULL,
  `version` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_payment_dispute_transaction` (`transaction_id`),
  KEY `idx_payment_dispute_phno` (`phno`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
