-- A customer's standing permission for the shop to charge their PhonePe account within limits (MandateService).
CREATE TABLE IF NOT EXISTS `payment_mandate` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `payer_phno` bigint NOT NULL,
  `merchant` varchar(40) DEFAULT NULL,
  `per_charge_limit` decimal(19,2) DEFAULT NULL,
  `monthly_limit` decimal(19,2) DEFAULT NULL,
  `charged_this_month` decimal(19,2) DEFAULT NULL,
  `charge_month` varchar(7) DEFAULT NULL,
  `valid_until` datetime(6) DEFAULT NULL,
  `status` varchar(10) NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `last_charge_at` datetime(6) DEFAULT NULL,
  `revoked_at` datetime(6) DEFAULT NULL,
  `version` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_payment_mandate_payer` (`payer_phno`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
